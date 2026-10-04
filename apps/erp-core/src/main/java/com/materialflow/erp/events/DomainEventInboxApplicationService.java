package com.materialflow.erp.events;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.materialflow.erp.audit.AuditOutboxWriter;
import com.materialflow.erp.persistence.HotPathMapper;
import com.materialflow.inventory.InventoryBalance;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DomainEventInboxApplicationService {
    private static final String CONSUMER = "erp-core-backorder-reallocation-v1";
    private static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final JdbcTemplate jdbc;
    private final HotPathMapper hotPath;
    private final ObjectMapper objectMapper;
    private final AuditOutboxWriter events;
    private final MeterRegistry meters;
    private final Duration reservationTtl;

    public DomainEventInboxApplicationService(
            JdbcTemplate jdbc,
            HotPathMapper hotPath,
            ObjectMapper objectMapper,
            AuditOutboxWriter events,
            MeterRegistry meters,
            @Value("${materialflow.reservation.ttl:PT2H}") Duration reservationTtl) {
        this.jdbc = jdbc;
        this.hotPath = hotPath;
        this.objectMapper = objectMapper;
        this.events = events;
        this.meters = meters;
        this.reservationTtl = reservationTtl;
    }

    @Transactional
    public ProcessingResult consume(String rawEnvelope) {
        JsonNode envelope = parse(rawEnvelope);
        UUID eventId = requiredUuid(envelope, "eventId");
        UUID organizationId = requiredUuid(envelope, "organizationId");
        int inserted = jdbc.update("""
                INSERT INTO consumer_inbox (event_id, consumer_name, organization_id)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, eventId, CONSUMER, organizationId);
        if (inserted == 0) {
            countAfterCommit("event_duplicate_total");
            return new ProcessingResult(eventId, true, 0);
        }
        int reallocated = 0;
        if ("InventoryReceived".equals(envelope.path("eventType").asText())) {
            UUID purchaseOrderId = requiredUuid(envelope, "aggregateId");
            reallocated = reallocate(organizationId, purchaseOrderId, eventId);
        }
        countAfterCommit("event_processed_total");
        return new ProcessingResult(eventId, false, reallocated);
    }

    private void countAfterCommit(String name) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    meters.counter(name, "consumer", CONSUMER).increment();
                }
            });
        } else {
            meters.counter(name, "consumer", CONSUMER).increment();
        }
    }

    private int reallocate(UUID organizationId, UUID purchaseOrderId, UUID causationId) {
        List<Candidate> candidates = jdbc.query("""
                SELECT ps.id, ps.order_id, ps.order_line_id, ps.material_id,
                       prl.warehouse_id, prl.location_id
                  FROM purchase_orders po
                  JOIN purchase_order_lines pol ON pol.purchase_order_id = po.id
                  JOIN purchase_request_lines prl ON prl.id = pol.purchase_request_line_id
                  JOIN procurement_suggestions ps ON ps.id = prl.suggestion_id
                 WHERE po.id = ? AND po.organization_id = ? AND ps.status = 'CONVERTED'
                 ORDER BY prl.warehouse_id, ps.material_id, prl.location_id, ps.created_at, ps.id
                """, (resultSet, row) -> new Candidate(
                        resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class),
                        resultSet.getObject(3, UUID.class), resultSet.getObject(4, UUID.class),
                        resultSet.getObject(5, UUID.class), resultSet.getObject(6, UUID.class)),
                purchaseOrderId, organizationId);
        int allocations = 0;
        Set<UUID> changedOrders = new HashSet<>();
        for (Candidate candidate : candidates) {
            String status = jdbc.query("""
                    SELECT status FROM sales_orders WHERE id = ? AND organization_id = ? FOR UPDATE
                    """, resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                    candidate.orderId(), organizationId);
            if (!("BACKORDERED".equals(status) || "PARTIALLY_ALLOCATED".equals(status))) continue;
            OrderLine line = jdbc.query("""
                    SELECT ordered_quantity, allocated_quantity, version
                      FROM sales_order_lines WHERE id = ? AND organization_id = ? FOR UPDATE
                    """, resultSet -> resultSet.next() ? new OrderLine(resultSet.getBigDecimal(1),
                            resultSet.getBigDecimal(2), resultSet.getLong(3)) : null,
                    candidate.orderLineId(), organizationId);
            if (line == null) continue;
            BigDecimal needed = line.ordered().subtract(line.allocated());
            if (needed.signum() <= 0) continue;
            HotPathMapper.InventoryRow row = hotPath.lockInventory(organizationId, candidate.warehouseId(),
                    candidate.materialId(), candidate.locationId());
            if (row == null || row.onHand().subtract(row.reserved()).signum() <= 0) continue;
            var result = new InventoryBalance(row.onHand(), row.reserved(), row.inTransit(), row.version())
                    .reserve(needed, true);
            if (result.allocated().signum() <= 0) continue;
            if (hotPath.updateInventory(row.id(), result.balance().onHand(), result.balance().reserved(),
                    result.balance().inTransit(), row.version()) != 1) {
                throw new IllegalStateException("Inventory changed during backorder reallocation");
            }
            int updated = jdbc.update("""
                    UPDATE sales_order_lines
                       SET allocated_quantity = allocated_quantity + ?, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND version = ?
                       AND allocated_quantity + ? <= ordered_quantity
                    """, result.allocated(), candidate.orderLineId(), line.version(), result.allocated());
            if (updated != 1) throw new IllegalStateException("Order line changed during backorder reallocation");
            jdbc.update("""
                    INSERT INTO inventory_reservations
                        (id, organization_id, order_id, order_line_id, inventory_balance_id,
                         quantity, status, expires_at, fencing_token)
                    VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                    ON CONFLICT (order_line_id, inventory_balance_id) DO UPDATE
                       SET quantity = inventory_reservations.quantity + EXCLUDED.quantity,
                           status = CASE WHEN inventory_reservations.shipped_quantity > 0
                                         THEN 'PARTIALLY_SHIPPED' ELSE 'ACTIVE' END,
                           expires_at = EXCLUDED.expires_at,
                           fencing_token = EXCLUDED.fencing_token,
                           version = inventory_reservations.version + 1,
                           updated_at = CURRENT_TIMESTAMP
                    """, UUID.randomUUID(), organizationId, candidate.orderId(), candidate.orderLineId(), row.id(),
                    result.allocated(), Timestamp.from(Instant.now().plus(reservationTtl)), result.fencingToken());
            jdbc.update("""
                    INSERT INTO inventory_ledger
                        (id, organization_id, inventory_balance_id, reference_type, reference_id,
                         movement_type, quantity, on_hand_after, reserved_after, reason, actor_id)
                    VALUES (?, ?, ?, 'SALES_ORDER', ?, 'RESERVE', ?, ?, ?,
                            'Backorder reallocation after purchase receipt', ?)
                    """, UUID.randomUUID(), organizationId, row.id(), candidate.orderId(), result.allocated(),
                    result.balance().onHand(), result.balance().reserved(), SYSTEM_ACTOR);
            BigDecimal remaining = needed.subtract(result.allocated());
            if (remaining.signum() == 0) {
                jdbc.update("""
                        UPDATE procurement_suggestions SET status = 'FULFILLED', version = version + 1,
                               updated_at = CURRENT_TIMESTAMP WHERE id = ?
                        """, candidate.suggestionId());
            } else {
                jdbc.update("""
                        UPDATE procurement_suggestions SET shortage_quantity = ?, version = version + 1,
                               updated_at = CURRENT_TIMESTAMP WHERE id = ?
                        """, remaining, candidate.suggestionId());
            }
            changedOrders.add(candidate.orderId());
            allocations++;
        }
        for (UUID orderId : changedOrders) {
            boolean fullyAllocated = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT bool_and(allocated_quantity = ordered_quantity)
                      FROM sales_order_lines WHERE order_id = ?
                    """, Boolean.class, orderId));
            String status = fullyAllocated ? "RESERVED" : "PARTIALLY_ALLOCATED";
            jdbc.update("""
                    UPDATE sales_orders SET status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND organization_id = ? AND status IN ('BACKORDERED', 'PARTIALLY_ALLOCATED')
                    """, status, orderId, organizationId);
            Long version = jdbc.queryForObject("SELECT version FROM sales_orders WHERE id = ?", Long.class, orderId);
            events.write(organizationId, SYSTEM_ACTOR, "SALES_ORDER", orderId, version == null ? 1 : version,
                    "BackorderReallocated", Map.of(), Map.of("orderId", orderId, "status", status),
                    "Inventory received from approved procurement", causationId, orderId);
        }
        return allocations;
    }

    private JsonNode parse(String value) {
        try {
            JsonNode envelope = objectMapper.readTree(value);
            if (envelope == null || !envelope.isObject()) {
                throw new IllegalArgumentException("Event envelope must be a JSON object");
            }
            return envelope;
        } catch (Exception exception) {
            throw new InvalidEventEnvelopeException("Malformed event envelope", exception);
        }
    }

    private UUID requiredUuid(JsonNode node, String field) {
        try {
            return UUID.fromString(node.path(field).asText());
        } catch (RuntimeException exception) {
            throw new InvalidEventEnvelopeException("Event field is missing or invalid: " + field, exception);
        }
    }

    public record ProcessingResult(UUID eventId, boolean duplicate, int reallocatedLines) {
    }

    private record Candidate(UUID suggestionId, UUID orderId, UUID orderLineId, UUID materialId,
                             UUID warehouseId, UUID locationId) {
    }

    private record OrderLine(BigDecimal ordered, BigDecimal allocated, long version) {
    }
}
