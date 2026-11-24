package com.materialflow.erp.approval;

import com.materialflow.approval.ApprovalStatus;
import com.materialflow.erp.common.BusinessException;
import com.materialflow.erp.common.PageResponse;
import com.materialflow.identity.ActorContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the sales-order approval queue; decisions stay in {@link ApprovalApplicationService}. */
@Service
public class ApprovalQueryApplicationService {
    private static final String DECIDE_ROLE = "APPROVAL_DECIDE";
    // The actor is in the frozen approver set, is not the applicant and has not decided yet.
    private static final String SELECT = """
            SELECT a.id, a.object_id, o.order_number, c.name AS customer_name, o.total_amount, a.applicant_id,
                   a.status, a.mode, a.required_approvals, a.approved_count, a.created_at, a.version,
                   (a.status = 'PENDING'
                    AND a.applicant_id <> ?
                    AND a.resolved_approvers @> jsonb_build_array(CAST(? AS text))
                    AND NOT EXISTS (SELECT 1 FROM approval_decisions d
                                     WHERE d.approval_instance_id = a.id AND d.approver_id = ?)) AS assigned
              FROM approval_instances a
              JOIN sales_orders o ON o.id = a.object_id AND o.organization_id = a.organization_id
              JOIN customers c ON c.id = o.customer_id AND c.organization_id = o.organization_id
            """;

    private final JdbcTemplate jdbc;

    public ApprovalQueryApplicationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<ApprovalSummaryResponse> list(ApprovalStatus status, int page, int size, ActorContext actor) {
        StringBuilder where = new StringBuilder(" WHERE a.organization_id = ? AND a.object_type = 'SALES_ORDER'");
        List<Object> filters = new ArrayList<>(List.of(actor.organizationId()));
        if (status != null) {
            where.append(" AND a.status = ?");
            filters.add(status.name());
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM approval_instances a" + where, Long.class,
                filters.toArray());
        List<Object> arguments = new ArrayList<>(actorArguments(actor));
        arguments.addAll(filters);
        arguments.add(size);
        arguments.add(PageResponse.offset(page, size));
        List<ApprovalSummaryResponse> items = jdbc.query(
                SELECT + where + " ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?",
                (resultSet, row) -> summary(resultSet, actor), arguments.toArray());
        return PageResponse.of(items, page, size, total == null ? 0 : total);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApprovalDetailResponse get(UUID approvalId, ActorContext actor) {
        List<Object> arguments = new ArrayList<>(actorArguments(actor));
        arguments.add(approvalId);
        arguments.add(actor.organizationId());
        List<ApprovalSummaryResponse> found = jdbc.query(
                SELECT + " WHERE a.id = ? AND a.organization_id = ? AND a.object_type = 'SALES_ORDER'",
                (resultSet, row) -> summary(resultSet, actor), arguments.toArray());
        if (found.isEmpty()) {
            throw BusinessException.notFound("Approval instance");
        }
        List<ApprovalDetailResponse.Decision> decisions = jdbc.query("""
                SELECT approver_id, decision, reason, created_at
                  FROM approval_decisions
                 WHERE approval_instance_id = ? AND organization_id = ?
                 ORDER BY created_at, id
                """, (resultSet, row) -> new ApprovalDetailResponse.Decision(
                        resultSet.getObject("approver_id", UUID.class), resultSet.getString("decision"),
                        resultSet.getString("reason"), resultSet.getObject("created_at", OffsetDateTime.class)),
                approvalId, actor.organizationId());
        return new ApprovalDetailResponse(found.getFirst(), decisions);
    }

    private List<Object> actorArguments(ActorContext actor) {
        return List.of(actor.userId(), actor.userId().toString(), actor.userId());
    }

    private ApprovalSummaryResponse summary(ResultSet resultSet, ActorContext actor) throws SQLException {
        return new ApprovalSummaryResponse(
                resultSet.getObject("id", UUID.class), resultSet.getObject("object_id", UUID.class),
                resultSet.getString("order_number"), resultSet.getString("customer_name"),
                resultSet.getBigDecimal("total_amount"), resultSet.getObject("applicant_id", UUID.class),
                resultSet.getString("status"), resultSet.getString("mode"), resultSet.getInt("required_approvals"),
                resultSet.getInt("approved_count"), resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getLong("version"),
                actor.roles().contains(DECIDE_ROLE) && resultSet.getBoolean("assigned"));
    }
}
