package com.materialflow.erp.reservation;

import com.materialflow.credit.CreditAccount;
import com.materialflow.erp.audit.AuditOutboxWriter;
import com.materialflow.erp.audit.IdempotencyService;
import com.materialflow.erp.common.BusinessException;
import com.materialflow.erp.order.OrderApplicationService;
import com.materialflow.erp.order.OrderResponse;
import com.materialflow.erp.persistence.HotPathMapper;
import com.materialflow.identity.ActorContext;
import com.materialflow.inventory.InventoryBalance;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationReleaseApplicationService {
    private static final Set<String> RELEASABLE = Set.of(
            "PENDING_APPROVAL", "RESERVED", "PARTIALLY_ALLOCATED", "BACKORDERED");
    private final JdbcTemplate jdbc;
    private final HotPathMapper hotPath;
    private final IdempotencyService idempotency;
    private final AuditOutboxWriter events;
    private final OrderApplicationService orders;

    public ReservationReleaseApplicationService(
            JdbcTemplate jdbc,
            HotPathMapper hotPath,
            IdempotencyService idempotency,
            AuditOutboxWriter events,
            OrderApplicationService orders) {
        this.jdbc = jdbc;
        this.hotPath = hotPath;
        this.idempotency = idempotency;
        this.events = events;
        this.orders = orders;
    }

    @Transactional
    public OrderResponse cancel(
            UUID orderId,
            CancelOrderRequest request,
            String idempotencyKey,
            ActorContext actor) {
        actor.requireRole("SALES_ORDER_WRITE");
        String operation = "sales-order.cancel." + orderId;
        var attempt = idempotency.begin(actor.organizationId(), operation, idempotencyKey,
                request, OrderResponse.class);
        if (attempt.replay()) {
            return attempt.response();
        }
        OrderResponse response = release(orderId, actor.organizationId(), actor.userId(), "CANCELLED",
                request.reason(), actor);
        idempotency.complete(actor.organizationId(), operation, idempotencyKey, response, 200, orderId);
        return response;
    }

    @Transactional
    public boolean expire(UUID orderId, UUID organizationId, UUID systemActor) {
        try {
            release(orderId, organizationId, systemActor, "EXPIRED", "Reservation TTL elapsed", null);
            return true;
        } catch (BusinessException exception) {
            if ("ORDER_NOT_RELEASABLE".equals(exception.errorCode())) {
                return false;
            }
            throw exception;
        }
    }

    private OrderResponse release(
            UUID orderId,
            UUID organizationId,
            UUID actorId,
            String targetStatus,
            String reason,
            ActorContext actor) {
        OrderRow preliminary = loadOrder(orderId, organizationId, false);
        HotPathMapper.CreditRow creditRow = hotPath.lockCredit(organizationId, preliminary.customerId());
        if (creditRow == null) {
            throw BusinessException.notFound("Customer credit account");
        }
        OrderRow order = loadOrder(orderId, organizationId, true);
        if (!RELEASABLE.contains(order.status())) {
            throw BusinessException.conflict("ORDER_NOT_RELEASABLE", "Order reservations cannot be released from the current state");
        }

        List<ReservationRow> reservations = loadOpenReservations(orderId, organizationId);
        if (actor != null) {
            reservations.forEach(row -> actor.requireWarehouse(row.key().warehouseId()));
        }
        List<BalanceKey> keys = reservations.stream().map(ReservationRow::key).distinct()
                .sorted(Comparator.naturalOrder()).toList();
        Map<BalanceKey, HotPathMapper.InventoryRow> balances = new HashMap<>();
        for (BalanceKey key : keys) {
            HotPathMapper.InventoryRow balance = hotPath.lockInventory(organizationId, key.warehouseId(),
                    key.materialId(), key.locationId());
            if (balance == null) {
                throw BusinessException.notFound("Inventory balance");
            }
            balances.put(key, balance);
        }

        Map<BalanceKey, BigDecimal> quantityByBalance = new HashMap<>();
        for (ReservationRow reservation : reservations) {
            BigDecimal remaining = reservation.quantity().subtract(reservation.shippedQuantity());
            if (remaining.signum() > 0) {
                quantityByBalance.merge(reservation.key(), remaining, BigDecimal::add);
            }
        }
        for (Map.Entry<BalanceKey, BigDecimal> entry : quantityByBalance.entrySet()) {
            HotPathMapper.InventoryRow row = balances.get(entry.getKey());
            InventoryBalance current = new InventoryBalance(row.onHand(), row.reserved(), row.inTransit(), row.version());
            InventoryBalance released;
            try {
                released = current.release(entry.getValue(), row.version());
            } catch (RuntimeException exception) {
                throw BusinessException.conflict("INVENTORY_RELEASE_CONFLICT", exception.getMessage());
            }
            if (hotPath.updateInventory(row.id(), released.onHand(), released.reserved(), released.inTransit(),
                    row.version()) != 1) {
                throw BusinessException.conflict("INVENTORY_CONCURRENT_UPDATE", "Inventory changed concurrently");
            }
            jdbc.update("""
                    INSERT INTO inventory_ledger
                        (id, organization_id, inventory_balance_id, reference_type, reference_id,
                         movement_type, quantity, on_hand_after, reserved_after, reason, actor_id)
                    VALUES (?, ?, ?, 'SALES_ORDER', ?, 'RELEASE', ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), organizationId, row.id(), orderId, entry.getValue(),
                    released.onHand(), released.reserved(), reason, actorId);
        }

        String reservationStatus = "EXPIRED".equals(targetStatus) ? "EXPIRED" : "RELEASED";
        for (ReservationRow reservation : reservations) {
            BigDecimal remaining = reservation.quantity().subtract(reservation.shippedQuantity());
            int updated = jdbc.update("""
                    UPDATE inventory_reservations
                       SET status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND version = ? AND status IN ('ACTIVE', 'PARTIALLY_SHIPPED')
                    """, reservationStatus, reservation.id(), reservation.version());
            if (updated != 1) {
                throw BusinessException.conflict("RESERVATION_CONCURRENT_UPDATE", "Reservation changed concurrently");
            }
            jdbc.update("""
                    UPDATE sales_order_lines
                       SET allocated_quantity = allocated_quantity - ?, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND allocated_quantity - ? >= fulfilled_quantity
                    """, remaining, reservation.orderLineId(), remaining);
        }

        if (!"PENDING_APPROVAL".equals(order.status())) {
            CreditAccount current = new CreditAccount(creditRow.creditLimit(), creditRow.creditReserved(),
                    creditRow.creditUsed(), creditRow.overdueAmount(), creditRow.frozen(), creditRow.version());
            CreditAccount released;
            try {
                released = current.release(order.totalAmount());
            } catch (RuntimeException exception) {
                throw BusinessException.conflict("CREDIT_RELEASE_CONFLICT", exception.getMessage());
            }
            if (hotPath.updateCredit(creditRow.id(), released.creditReserved(), released.creditUsed(),
                    released.overdueAmount(), creditRow.version()) != 1) {
                throw BusinessException.conflict("CREDIT_CONCURRENT_UPDATE", "Credit changed concurrently");
            }
            jdbc.update("""
                    INSERT INTO credit_ledger
                        (id, organization_id, credit_account_id, order_id, entry_type, amount,
                         balance_reserved, balance_used, reason, actor_id)
                    VALUES (?, ?, ?, ?, 'RELEASE', ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), organizationId, creditRow.id(), orderId, order.totalAmount(),
                    released.creditReserved(), released.creditUsed(), reason, actorId);
        }

        int orderUpdated = jdbc.update("""
                UPDATE sales_orders SET status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND organization_id = ? AND version = ?
                """, targetStatus, orderId, organizationId, order.version());
        if (orderUpdated != 1) {
            throw BusinessException.conflict("ORDER_CONCURRENT_UPDATE", "Order changed concurrently");
        }
        if (order.approvalId() != null) {
            jdbc.update("""
                    UPDATE approval_instances SET status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND organization_id = ? AND status = 'PENDING'
                    """, "EXPIRED".equals(targetStatus) ? "EXPIRED" : "WITHDRAWN",
                    order.approvalId(), organizationId);
        }
        jdbc.update("""
                UPDATE procurement_suggestions SET status = 'DISMISSED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE order_id = ? AND organization_id = ? AND status = 'OPEN'
                """, orderId, organizationId);
        events.write(organizationId, actorId, "SALES_ORDER", orderId, order.version() + 1,
                "EXPIRED".equals(targetStatus) ? "SalesOrderReservationExpired" : "SalesOrderCancelled",
                Map.of("status", order.status()),
                Map.of("status", targetStatus, "releasedReservations", reservations.size()),
                reason, null, orderId);
        return orders.loadResponse(orderId, organizationId);
    }

    private OrderRow loadOrder(UUID orderId, UUID organizationId, boolean lock) {
        String sql = """
                SELECT customer_id, status, total_amount, version, approval_instance_id
                  FROM sales_orders WHERE id = ? AND organization_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        OrderRow row = jdbc.query(sql, resultSet -> resultSet.next()
                ? new OrderRow(resultSet.getObject(1, UUID.class), resultSet.getString(2),
                        resultSet.getBigDecimal(3), resultSet.getLong(4),
                        resultSet.getObject(5, UUID.class)) : null, orderId, organizationId);
        if (row == null) {
            throw BusinessException.notFound("Sales order");
        }
        return row;
    }

    private List<ReservationRow> loadOpenReservations(UUID orderId, UUID organizationId) {
        return jdbc.query("""
                SELECT r.id, r.order_line_id, r.quantity, r.shipped_quantity, r.version,
                       b.warehouse_id, b.material_id, b.location_id
                  FROM inventory_reservations r
                  JOIN inventory_balances b ON b.id = r.inventory_balance_id
                 WHERE r.order_id = ? AND r.organization_id = ?
                   AND r.status IN ('ACTIVE', 'PARTIALLY_SHIPPED')
                """, (resultSet, rowNumber) -> new ReservationRow(
                        resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class),
                        resultSet.getBigDecimal(3), resultSet.getBigDecimal(4), resultSet.getLong(5),
                        new BalanceKey(resultSet.getObject(6, UUID.class), resultSet.getObject(7, UUID.class),
                                resultSet.getObject(8, UUID.class))), orderId, organizationId);
    }

    private record OrderRow(UUID customerId, String status, BigDecimal totalAmount, long version, UUID approvalId) {
    }

    private record ReservationRow(
            UUID id,
            UUID orderLineId,
            BigDecimal quantity,
            BigDecimal shippedQuantity,
            long version,
            BalanceKey key) {
    }

    private record BalanceKey(UUID warehouseId, UUID materialId, UUID locationId)
            implements Comparable<BalanceKey> {
        @Override
        public int compareTo(BalanceKey other) {
            int warehouse = warehouseId.compareTo(other.warehouseId);
            if (warehouse != 0) return warehouse;
            int material = materialId.compareTo(other.materialId);
            return material != 0 ? material : locationId.compareTo(other.locationId);
        }
    }
}
