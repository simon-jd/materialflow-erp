package com.materialflow.erp.fulfillment;

import com.materialflow.credit.CreditAccount;
import com.materialflow.erp.audit.AuditOutboxWriter;
import com.materialflow.erp.audit.IdempotencyService;
import com.materialflow.erp.common.BusinessException;
import com.materialflow.erp.persistence.HotPathMapper;
import com.materialflow.identity.ActorContext;
import com.materialflow.inventory.InventoryBalance;
import com.materialflow.sales.OrderStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FulfillmentApplicationService {
    private final JdbcTemplate jdbc;
    private final HotPathMapper hotPath;
    private final IdempotencyService idempotency;
    private final AuditOutboxWriter events;

    public FulfillmentApplicationService(
            JdbcTemplate jdbc,
            HotPathMapper hotPath,
            IdempotencyService idempotency,
            AuditOutboxWriter events) {
        this.jdbc = jdbc;
        this.hotPath = hotPath;
        this.idempotency = idempotency;
        this.events = events;
    }

    @Transactional
    public ShipmentResponse ship(
            UUID orderId,
            CreateShipmentRequest request,
            String idempotencyKey,
            ActorContext actor) {
        actor.requireRole("WAREHOUSE_OPERATE");
        String operation = "fulfillment.ship." + orderId;
        var attempt = idempotency.begin(actor.organizationId(), operation, idempotencyKey,
                request, ShipmentResponse.class);
        if (attempt.replay()) {
            return attempt.response();
        }
        if (request.lines().stream().map(CreateShipmentRequest.Line::reservationId).distinct().count()
                != request.lines().size()) {
            throw BusinessException.conflict("DUPLICATE_RESERVATION_LINE", "A reservation can appear only once per shipment");
        }

        OrderRow preliminary = loadOrder(orderId, actor.organizationId(), false);
        HotPathMapper.CreditRow creditRow = hotPath.lockCredit(actor.organizationId(), preliminary.customerId());
        if (creditRow == null) {
            throw BusinessException.notFound("Customer credit account");
        }
        OrderRow order = loadOrder(orderId, actor.organizationId(), true);
        if (!new HashSet<>(List.of(OrderStatus.RESERVED.name(), OrderStatus.PARTIALLY_ALLOCATED.name(),
                OrderStatus.PARTIALLY_FULFILLED.name())).contains(order.status())) {
            throw BusinessException.conflict("ORDER_NOT_SHIPPABLE", "Order is not in a shippable state");
        }

        List<ReservationRow> metadata = request.lines().stream()
                .map(line -> loadReservation(line.reservationId(), orderId, actor.organizationId(), false))
                .toList();
        metadata.forEach(row -> actor.requireWarehouse(row.warehouseId()));
        List<BalanceKey> balanceKeys = metadata.stream()
                .map(row -> new BalanceKey(row.warehouseId(), row.materialId(), row.locationId()))
                .distinct().sorted().toList();
        Map<BalanceKey, HotPathMapper.InventoryRow> balances = new HashMap<>();
        for (BalanceKey key : balanceKeys) {
            HotPathMapper.InventoryRow balance = hotPath.lockInventory(actor.organizationId(), key.warehouseId(),
                    key.materialId(), key.locationId());
            if (balance == null) {
                throw BusinessException.notFound("Inventory balance");
            }
            balances.put(key, balance);
        }

        List<ReservationRow> reservations = new ArrayList<>();
        BigDecimal capturedAmount = BigDecimal.ZERO;
        Map<BalanceKey, BigDecimal> shippedByBalance = new HashMap<>();
        for (CreateShipmentRequest.Line requestLine : request.lines()) {
            ReservationRow reservation = loadReservation(requestLine.reservationId(), orderId,
                    actor.organizationId(), true);
            BigDecimal remaining = reservation.quantity().subtract(reservation.shippedQuantity());
            if (!("ACTIVE".equals(reservation.status()) || "PARTIALLY_SHIPPED".equals(reservation.status()))
                    || remaining.compareTo(requestLine.quantity()) < 0) {
                throw BusinessException.conflict("RESERVATION_NOT_SHIPPABLE",
                        "Requested quantity exceeds the active reservation");
            }
            reservation = reservation.withRequested(requestLine.quantity());
            reservations.add(reservation);
            BalanceKey key = new BalanceKey(reservation.warehouseId(), reservation.materialId(), reservation.locationId());
            shippedByBalance.merge(key, requestLine.quantity(), BigDecimal::add);
            capturedAmount = capturedAmount.add(requestLine.quantity().multiply(reservation.unitPrice())
                    .multiply(BigDecimal.ONE.subtract(reservation.discountRate())));
        }
        capturedAmount = capturedAmount.setScale(2, RoundingMode.HALF_UP);

        for (Map.Entry<BalanceKey, BigDecimal> entry : shippedByBalance.entrySet()) {
            HotPathMapper.InventoryRow row = balances.get(entry.getKey());
            InventoryBalance current = new InventoryBalance(row.onHand(), row.reserved(), row.inTransit(), row.version());
            InventoryBalance next;
            try {
                next = current.ship(entry.getValue(), current.version());
            } catch (RuntimeException exception) {
                throw BusinessException.conflict("INVENTORY_SHIPMENT_CONFLICT", exception.getMessage());
            }
            if (hotPath.updateInventory(row.id(), next.onHand(), next.reserved(), next.inTransit(), row.version()) != 1) {
                throw BusinessException.conflict("INVENTORY_CONCURRENT_UPDATE", "Inventory changed concurrently");
            }
            jdbc.update("""
                    INSERT INTO inventory_ledger
                        (id, organization_id, inventory_balance_id, reference_type, reference_id,
                         movement_type, quantity, on_hand_after, reserved_after, reason, actor_id)
                    VALUES (?, ?, ?, 'SHIPMENT', ?, 'SHIP', ?, ?, ?, 'Outbound shipment', ?)
                    """, UUID.randomUUID(), actor.organizationId(), row.id(), orderId, entry.getValue(),
                    next.onHand(), next.reserved(), actor.userId());
        }

        CreditAccount captured;
        try {
            captured = new CreditAccount(creditRow.creditLimit(), creditRow.creditReserved(), creditRow.creditUsed(),
                    creditRow.overdueAmount(), creditRow.frozen(), creditRow.version()).capture(capturedAmount);
        } catch (RuntimeException exception) {
            throw BusinessException.conflict("CREDIT_CAPTURE_CONFLICT", exception.getMessage());
        }
        if (hotPath.updateCredit(creditRow.id(), captured.creditReserved(), captured.creditUsed(),
                captured.overdueAmount(), creditRow.version()) != 1) {
            throw BusinessException.conflict("CREDIT_CONCURRENT_UPDATE", "Credit changed concurrently");
        }
        jdbc.update("""
                INSERT INTO credit_ledger
                    (id, organization_id, credit_account_id, order_id, entry_type, amount,
                     balance_reserved, balance_used, reason, actor_id)
                VALUES (?, ?, ?, ?, 'CAPTURE', ?, ?, ?, 'Shipment captured reserved credit', ?)
                """, UUID.randomUUID(), actor.organizationId(), creditRow.id(), orderId, capturedAmount,
                captured.creditReserved(), captured.creditUsed(), actor.userId());

        UUID shipmentId = UUID.randomUUID();
        String shipmentNumber = "SHP-" + shipmentId.toString().replace("-", "").substring(0, 20).toUpperCase();
        jdbc.update("""
                INSERT INTO shipments (id, organization_id, order_id, shipment_number, status)
                VALUES (?, ?, ?, ?, 'SHIPPED')
                """, shipmentId, actor.organizationId(), orderId, shipmentNumber);
        List<ShipmentResponse.Line> responseLines = new ArrayList<>();
        for (ReservationRow reservation : reservations) {
            BigDecimal shippedAfter = reservation.shippedQuantity().add(reservation.requested());
            String reservationStatus = shippedAfter.compareTo(reservation.quantity()) == 0
                    ? "SHIPPED" : "PARTIALLY_SHIPPED";
            int reservationUpdated = jdbc.update("""
                    UPDATE inventory_reservations
                       SET shipped_quantity = ?, status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND version = ?
                    """, shippedAfter, reservationStatus, reservation.id(), reservation.version());
            if (reservationUpdated != 1) {
                throw BusinessException.conflict("RESERVATION_CONCURRENT_UPDATE",
                        "Reservation changed concurrently");
            }
            int orderLineUpdated = jdbc.update("""
                    UPDATE sales_order_lines
                       SET fulfilled_quantity = fulfilled_quantity + ?, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND fulfilled_quantity + ? <= allocated_quantity
                    """, reservation.requested(), reservation.orderLineId(), reservation.requested());
            if (orderLineUpdated != 1) {
                throw BusinessException.conflict("FULFILLMENT_CONCURRENT_UPDATE",
                        "Fulfillment quantity exceeds the allocated quantity");
            }
            jdbc.update("""
                    INSERT INTO shipment_lines
                        (id, organization_id, shipment_id, order_line_id, reservation_id, quantity)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), actor.organizationId(), shipmentId, reservation.orderLineId(),
                    reservation.id(), reservation.requested());
            responseLines.add(new ShipmentResponse.Line(reservation.id(), reservation.orderLineId(), reservation.requested()));
        }

        boolean fulfilled = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT bool_and(fulfilled_quantity = ordered_quantity)
                  FROM sales_order_lines WHERE order_id = ?
                """, Boolean.class, orderId));
        String orderStatus = fulfilled ? OrderStatus.FULFILLED.name() : OrderStatus.PARTIALLY_FULFILLED.name();
        jdbc.update("""
                UPDATE sales_orders SET status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND organization_id = ?
                """, orderStatus, orderId, actor.organizationId());

        ShipmentResponse response = new ShipmentResponse(shipmentId, orderId, shipmentNumber, "SHIPPED",
                capturedAmount, responseLines);
        events.write(actor.organizationId(), actor.userId(), "SHIPMENT", shipmentId, 1,
                "ShipmentDispatched", Map.of(),
                Map.of("shipmentId", shipmentId, "orderId", orderId, "quantityLines", responseLines.size(),
                        "orderStatus", orderStatus),
                "Outbound shipment", null, orderId);
        idempotency.complete(actor.organizationId(), operation, idempotencyKey, response, 201, shipmentId);
        return response;
    }

    private OrderRow loadOrder(UUID orderId, UUID organizationId, boolean lock) {
        String sql = """
                SELECT customer_id, status FROM sales_orders WHERE id = ? AND organization_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        OrderRow row = jdbc.query(sql, resultSet -> resultSet.next()
                ? new OrderRow(resultSet.getObject(1, UUID.class), resultSet.getString(2)) : null,
                orderId, organizationId);
        if (row == null) {
            throw BusinessException.notFound("Sales order");
        }
        return row;
    }

    private ReservationRow loadReservation(UUID reservationId, UUID orderId, UUID organizationId, boolean lock) {
        String sql = """
                SELECT r.id, r.order_line_id, r.quantity, r.shipped_quantity, r.status, r.version,
                       b.warehouse_id, b.material_id, b.location_id, l.unit_price, l.discount_rate
                  FROM inventory_reservations r
                  JOIN inventory_balances b ON b.id = r.inventory_balance_id
                  JOIN sales_order_lines l ON l.id = r.order_line_id
                 WHERE r.id = ? AND r.order_id = ? AND r.organization_id = ?
                """ + (lock ? " FOR UPDATE OF r" : "");
        ReservationRow row = jdbc.query(sql, resultSet -> resultSet.next()
                ? new ReservationRow(resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class),
                        resultSet.getBigDecimal(3), resultSet.getBigDecimal(4), resultSet.getString(5),
                        resultSet.getLong(6), resultSet.getObject(7, UUID.class),
                        resultSet.getObject(8, UUID.class), resultSet.getObject(9, UUID.class),
                        resultSet.getBigDecimal(10), resultSet.getBigDecimal(11), BigDecimal.ZERO)
                : null, reservationId, orderId, organizationId);
        if (row == null) {
            throw BusinessException.notFound("Inventory reservation");
        }
        return row;
    }

    private record OrderRow(UUID customerId, String status) {
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

    private record ReservationRow(
            UUID id,
            UUID orderLineId,
            BigDecimal quantity,
            BigDecimal shippedQuantity,
            String status,
            long version,
            UUID warehouseId,
            UUID materialId,
            UUID locationId,
            BigDecimal unitPrice,
            BigDecimal discountRate,
            BigDecimal requested) {
        ReservationRow withRequested(BigDecimal value) {
            return new ReservationRow(id, orderLineId, quantity, shippedQuantity, status, version,
                    warehouseId, materialId, locationId, unitPrice, discountRate, value);
        }
    }
}
