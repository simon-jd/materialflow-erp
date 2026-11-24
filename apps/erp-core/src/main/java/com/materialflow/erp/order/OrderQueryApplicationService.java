package com.materialflow.erp.order;

import com.materialflow.erp.common.PageResponse;
import com.materialflow.identity.ActorContext;
import com.materialflow.sales.OrderStatus;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-scoped order list for operator screens; writes stay in {@link OrderApplicationService}. */
@Service
public class OrderQueryApplicationService {
    private final JdbcTemplate jdbc;

    public OrderQueryApplicationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Newest first with the id as tie-breaker, so concurrent inserts never reorder a page.
     * Count and page share one snapshot.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<OrderSummaryResponse> list(OrderStatus status, int page, int size, ActorContext actor) {
        StringBuilder where = new StringBuilder(" WHERE o.organization_id = ?");
        List<Object> arguments = new ArrayList<>(List.of(actor.organizationId()));
        if (status != null) {
            where.append(" AND o.status = ?");
            arguments.add(status.name());
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM sales_orders o" + where, Long.class,
                arguments.toArray());
        arguments.add(size);
        arguments.add(PageResponse.offset(page, size));
        List<OrderSummaryResponse> items = jdbc.query("""
                SELECT o.id, o.order_number, o.customer_id, c.name AS customer_name, o.status, o.total_amount,
                       o.currency, o.approval_instance_id, o.created_at, o.version
                  FROM sales_orders o
                  JOIN customers c ON c.id = o.customer_id AND c.organization_id = o.organization_id
                """ + where + " ORDER BY o.created_at DESC, o.id DESC LIMIT ? OFFSET ?",
                (resultSet, row) -> new OrderSummaryResponse(
                        resultSet.getObject("id", UUID.class), resultSet.getString("order_number"),
                        resultSet.getObject("customer_id", UUID.class), resultSet.getString("customer_name"),
                        resultSet.getString("status"), resultSet.getBigDecimal("total_amount"),
                        resultSet.getString("currency"), resultSet.getObject("approval_instance_id", UUID.class),
                        resultSet.getObject("created_at", OffsetDateTime.class), resultSet.getLong("version")),
                arguments.toArray());
        return PageResponse.of(items, page, size, total == null ? 0 : total);
    }
}
