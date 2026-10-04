package com.materialflow.erp.approval;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.materialflow.approval.ApprovalDecision;
import com.materialflow.erp.audit.AuditOutboxWriter;
import com.materialflow.erp.audit.IdempotencyService;
import com.materialflow.erp.common.BusinessException;
import com.materialflow.erp.order.OrderApplicationService;
import com.materialflow.identity.ActorContext;
import com.materialflow.sales.OrderStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalApplicationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotency;
    private final OrderApplicationService orders;
    private final AuditOutboxWriter events;

    public ApprovalApplicationService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            IdempotencyService idempotency,
            OrderApplicationService orders,
            AuditOutboxWriter events) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
        this.orders = orders;
        this.events = events;
    }

    @Transactional
    public ApprovalResponse decide(
            UUID approvalId,
            ApprovalDecisionRequest request,
            String idempotencyKey,
            ActorContext actor) {
        actor.requireRole("APPROVAL_DECIDE");
        String operation = "approval.decision." + approvalId;
        var attempt = idempotency.begin(actor.organizationId(), operation, idempotencyKey,
                request, ApprovalResponse.class);
        if (attempt.replay()) {
            return attempt.response();
        }
        ApprovalRow row = lock(approvalId, actor.organizationId());
        if (!"PENDING".equals(row.status())) {
            throw BusinessException.conflict("APPROVAL_TERMINAL", "Approval is already terminal");
        }
        if (row.applicantId().equals(actor.userId())) {
            throw new BusinessException("MAKER_CHECKER_VIOLATION", HttpStatus.FORBIDDEN,
                    "An applicant cannot approve or reject their own request");
        }
        if (!row.approvers().contains(actor.userId())) {
            throw new BusinessException("NOT_ASSIGNED_APPROVER", HttpStatus.FORBIDDEN,
                    "The actor is not part of the frozen approver set");
        }
        if (request.decision() == ApprovalDecision.REJECT
                && (request.reason() == null || request.reason().isBlank())) {
            throw new BusinessException("REJECTION_REASON_REQUIRED", HttpStatus.BAD_REQUEST,
                    "A rejection reason is required");
        }

        jdbc.update("""
                INSERT INTO approval_decisions
                    (id, organization_id, approval_instance_id, approver_id, decision, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor.organizationId(), approvalId, actor.userId(),
                request.decision().name(), request.reason());

        int approvedCount = row.approvedCount();
        String status;
        if (request.decision() == ApprovalDecision.REJECT) {
            status = "REJECTED";
            jdbc.update("""
                    UPDATE approval_instances SET status = 'REJECTED', version = version + 1,
                           updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'PENDING'
                    """, approvalId);
            jdbc.update("""
                    UPDATE sales_orders SET status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND organization_id = ? AND status = 'PENDING_APPROVAL'
                    """, OrderStatus.REJECTED.name(), row.objectId(), actor.organizationId());
        } else {
            approvedCount++;
            boolean complete = approvedCount >= row.requiredApprovals();
            status = complete ? "APPROVED" : "PENDING";
            int updated = jdbc.update("""
                    UPDATE approval_instances
                       SET approved_count = ?, status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND status = 'PENDING' AND version = ?
                    """, approvedCount, status, approvalId, row.version());
            if (updated != 1) {
                throw BusinessException.conflict("APPROVAL_CONCURRENT_UPDATE", "Approval changed concurrently");
            }
            if (complete) {
                orders.allocateApprovedOrder(row.objectId(), actor.organizationId(), actor.userId(),
                        row.creditOverrideRequired());
            }
        }

        ApprovalResponse response = current(approvalId, actor.organizationId());
        events.write(actor.organizationId(), actor.userId(), "APPROVAL", approvalId, response.version(),
                "ApprovalDecisionRecorded", Map.of("status", row.status(), "approvedCount", row.approvedCount()),
                Map.of("status", status, "approvedCount", approvedCount, "decision", request.decision().name()),
                request.reason(), null, row.objectId());
        idempotency.complete(actor.organizationId(), operation, idempotencyKey, response, 200, approvalId);
        return response;
    }

    private ApprovalRow lock(UUID approvalId, UUID organizationId) {
        ApprovalRow row = jdbc.query("""
                SELECT object_id, applicant_id, status, required_approvals, approved_count,
                       resolved_approvers::text, version,
                       COALESCE((rule_inputs ->> 'creditOverrideRequired')::boolean, false)
                  FROM approval_instances WHERE id = ? AND organization_id = ? FOR UPDATE
                """, resultSet -> {
                    if (!resultSet.next()) {
                        return null;
                    }
                    return new ApprovalRow(resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class),
                            resultSet.getString(3), resultSet.getInt(4), resultSet.getInt(5),
                            parseApprovers(resultSet.getString(6)), resultSet.getLong(7),
                            resultSet.getBoolean(8));
                }, approvalId, organizationId);
        if (row == null) {
            throw BusinessException.notFound("Approval instance");
        }
        return row;
    }

    private ApprovalResponse current(UUID approvalId, UUID organizationId) {
        ApprovalResponse response = jdbc.query("""
                SELECT id, object_id, status, approved_count, version
                  FROM approval_instances WHERE id = ? AND organization_id = ?
                """, resultSet -> resultSet.next()
                        ? new ApprovalResponse(resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class),
                                resultSet.getString(3), resultSet.getInt(4), resultSet.getLong(5))
                        : null, approvalId, organizationId);
        if (response == null) {
            throw BusinessException.notFound("Approval instance");
        }
        return response;
    }

    private List<UUID> parseApprovers(String json) {
        try {
            List<String> values = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return values.stream().map(UUID::fromString).toList();
        } catch (JacksonException exception) {
            throw new IllegalStateException("Invalid frozen approver snapshot", exception);
        }
    }

    private record ApprovalRow(
            UUID objectId,
            UUID applicantId,
            String status,
            int requiredApprovals,
            int approvedCount,
            List<UUID> approvers,
            long version,
            boolean creditOverrideRequired) {
    }
}
