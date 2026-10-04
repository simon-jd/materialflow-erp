package com.materialflow.erp.procurement;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.materialflow.erp.audit.AuditOutboxWriter;
import com.materialflow.erp.audit.IdempotencyService;
import com.materialflow.erp.common.BusinessException;
import com.materialflow.erp.persistence.HotPathMapper;
import com.materialflow.identity.ActorContext;
import com.materialflow.inventory.InventoryBalance;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcurementApplicationService {
    private static final UUID PURCHASE_TEMPLATE = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private final JdbcTemplate jdbc;
    private final HotPathMapper hotPath;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotency;
    private final AuditOutboxWriter events;

    public ProcurementApplicationService(
            JdbcTemplate jdbc,
            HotPathMapper hotPath,
            ObjectMapper objectMapper,
            IdempotencyService idempotency,
            AuditOutboxWriter events) {
        this.jdbc = jdbc;
        this.hotPath = hotPath;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
        this.events = events;
    }

    @Transactional
    public PurchaseRequestResponse create(CreatePurchaseRequest request, String key, ActorContext actor) {
        actor.requireRole("PROCUREMENT_WRITE");
        var attempt = idempotency.begin(actor.organizationId(), "procurement.request", key,
                request, PurchaseRequestResponse.class);
        if (attempt.replay()) return attempt.response();
        Long supplier = jdbc.queryForObject("""
                SELECT count(*) FROM suppliers WHERE id = ? AND organization_id = ? AND active
                """, Long.class, request.supplierId(), actor.organizationId());
        if (supplier == null || supplier != 1) throw BusinessException.notFound("Supplier");

        List<Suggestion> suggestions = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (CreatePurchaseRequest.Line line : request.lines()) {
            Suggestion suggestion = lockSuggestion(line.suggestionId(), actor.organizationId());
            actor.requireWarehouse(suggestion.warehouseId());
            if (!"OPEN".equals(suggestion.status()) || line.quantity().compareTo(suggestion.shortage()) > 0) {
                throw BusinessException.conflict("SUGGESTION_NOT_CONVERTIBLE",
                        "Suggestion is closed or requested quantity exceeds the shortage");
            }
            suggestions.add(suggestion);
            total = total.add(line.quantity().multiply(line.unitCost()));
        }
        total = total.setScale(2, RoundingMode.HALF_UP);
        Template template = loadTemplate(actor.organizationId());
        UUID requestId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO approval_instances
                    (id, organization_id, object_type, object_id, template_id, template_version,
                     applicant_id, status, mode, required_approvals, rule_inputs, resolved_approvers)
                VALUES (?, ?, 'PURCHASE_REQUEST', ?, ?, ?, ?, 'PENDING', ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                """, approvalId, actor.organizationId(), requestId, template.id(), template.version(), actor.userId(),
                template.mode(), template.required(), json(Map.of("totalAmount", total)), json(template.approvers()));
        String requestNumber = "PR-" + requestId.toString().replace("-", "").substring(0, 20).toUpperCase();
        jdbc.update("""
                INSERT INTO purchase_requests
                    (id, organization_id, request_number, supplier_id, applicant_id, status,
                     total_amount, approval_instance_id)
                VALUES (?, ?, ?, ?, ?, 'PENDING_APPROVAL', ?, ?)
                """, requestId, actor.organizationId(), requestNumber, request.supplierId(), actor.userId(),
                total, approvalId);
        for (int index = 0; index < request.lines().size(); index++) {
            CreatePurchaseRequest.Line line = request.lines().get(index);
            Suggestion suggestion = suggestions.get(index);
            jdbc.update("""
                    INSERT INTO purchase_request_lines
                        (id, organization_id, purchase_request_id, suggestion_id, material_id,
                         warehouse_id, location_id, quantity, unit_cost)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), actor.organizationId(), requestId, suggestion.id(), suggestion.materialId(),
                    suggestion.warehouseId(), line.locationId(), line.quantity(), line.unitCost());
            jdbc.update("""
                    UPDATE procurement_suggestions SET status = 'CONVERTED', version = version + 1,
                           updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, suggestion.id());
        }
        PurchaseRequestResponse response = new PurchaseRequestResponse(requestId, requestNumber,
                "PENDING_APPROVAL", total, approvalId, null);
        events.write(actor.organizationId(), actor.userId(), "PURCHASE_REQUEST", requestId, 1,
                "PurchaseRequestApprovalRequested", Map.of(),
                Map.of("purchaseRequestId", requestId, "totalAmount", total, "approvalInstanceId", approvalId),
                "Purchase request created from shortage suggestions", null, requestId);
        idempotency.complete(actor.organizationId(), "procurement.request", key, response, 201, requestId);
        return response;
    }

    @Transactional
    public PurchaseRequestResponse approve(UUID requestId, String key, ActorContext actor) {
        actor.requireRole("PROCUREMENT_APPROVE");
        String operation = "procurement.approve." + requestId;
        var attempt = idempotency.begin(actor.organizationId(), operation, key, Map.of("requestId", requestId),
                PurchaseRequestResponse.class);
        if (attempt.replay()) return attempt.response();
        PurchaseRequestRow request = lockRequest(requestId, actor.organizationId());
        Approval approval = lockApproval(request.approvalId(), actor.organizationId());
        if (!"PENDING_APPROVAL".equals(request.status()) || !"PENDING".equals(approval.status())) {
            throw BusinessException.conflict("PURCHASE_APPROVAL_TERMINAL", "Purchase request is not pending approval");
        }
        if (request.applicantId().equals(actor.userId())) {
            throw BusinessException.conflict("MAKER_CHECKER_VIOLATION", "Applicant cannot approve their own purchase request");
        }
        if (!approval.approvers().contains(actor.userId())) {
            throw BusinessException.conflict("NOT_ASSIGNED_APPROVER", "Actor is not a frozen purchase approver");
        }
        jdbc.update("""
                INSERT INTO approval_decisions
                    (id, organization_id, approval_instance_id, approver_id, decision, reason)
                VALUES (?, ?, ?, ?, 'APPROVE', 'Purchase request approved')
                """, UUID.randomUUID(), actor.organizationId(), approval.id(), actor.userId());
        jdbc.update("""
                UPDATE approval_instances SET status = 'APPROVED', approved_count = 1,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, approval.id());
        jdbc.update("""
                UPDATE purchase_requests SET status = 'APPROVED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, requestId);

        UUID purchaseOrderId = UUID.randomUUID();
        String orderNumber = "PO-" + purchaseOrderId.toString().replace("-", "").substring(0, 20).toUpperCase();
        jdbc.update("""
                INSERT INTO purchase_orders
                    (id, organization_id, purchase_order_number, purchase_request_id, supplier_id, status)
                VALUES (?, ?, ?, ?, ?, 'ORDERED')
                """, purchaseOrderId, actor.organizationId(), orderNumber, requestId, request.supplierId());
        List<PurchaseRequestLine> requestLines = jdbc.query("""
                SELECT id, material_id, warehouse_id, location_id, quantity, unit_cost
                  FROM purchase_request_lines WHERE purchase_request_id = ? ORDER BY id
                """, (resultSet, rowNumber) -> new PurchaseRequestLine(
                        resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class),
                        resultSet.getObject(3, UUID.class), resultSet.getObject(4, UUID.class),
                        resultSet.getBigDecimal(5), resultSet.getBigDecimal(6)), requestId);
        for (PurchaseRequestLine line : requestLines) {
            jdbc.update("""
                    INSERT INTO purchase_order_lines
                        (id, organization_id, purchase_order_id, purchase_request_line_id,
                         material_id, warehouse_id, location_id, ordered_quantity, unit_cost)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), actor.organizationId(), purchaseOrderId,
                    line.id(), line.materialId(), line.warehouseId(), line.locationId(),
                    line.quantity(), line.unitCost());
        }
        PurchaseRequestResponse response = new PurchaseRequestResponse(requestId, request.requestNumber(),
                "APPROVED", request.totalAmount(), approval.id(), purchaseOrderId);
        events.write(actor.organizationId(), actor.userId(), "PURCHASE_ORDER", purchaseOrderId, 1,
                "PurchaseOrderCreated", Map.of(),
                Map.of("purchaseOrderId", purchaseOrderId, "purchaseRequestId", requestId,
                        "purchaseOrderNumber", orderNumber),
                "Approved purchase request converted to purchase order", null, requestId);
        idempotency.complete(actor.organizationId(), operation, key, response, 200, purchaseOrderId);
        return response;
    }

    @Transactional
    public PurchaseReceiptResponse receive(
            UUID purchaseOrderId,
            ReceivePurchaseOrderRequest request,
            String key,
            ActorContext actor) {
        actor.requireRole("WAREHOUSE_OPERATE");
        String operation = "procurement.receive." + purchaseOrderId;
        var attempt = idempotency.begin(actor.organizationId(), operation, key, request,
                PurchaseReceiptResponse.class);
        if (attempt.replay()) return attempt.response();
        String status = jdbc.query("""
                SELECT status FROM purchase_orders WHERE id = ? AND organization_id = ? FOR UPDATE
                """, resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                purchaseOrderId, actor.organizationId());
        if (!("ORDERED".equals(status) || "PARTIALLY_RECEIVED".equals(status))) {
            throw BusinessException.conflict("PURCHASE_ORDER_NOT_RECEIVABLE", "Purchase order is not open for receipts");
        }
        if (request.lines().stream().map(ReceivePurchaseOrderRequest.Line::purchaseOrderLineId).distinct().count()
                != request.lines().size()) {
            throw BusinessException.conflict("DUPLICATE_PURCHASE_LINE", "A purchase-order line can appear only once");
        }
        List<ReceiptLine> lines = request.lines().stream()
                .map(line -> loadPurchaseLine(line, purchaseOrderId, actor.organizationId())).toList();
        lines.forEach(line -> actor.requireWarehouse(line.warehouseId()));
        List<BalanceKey> keys = lines.stream().map(line -> new BalanceKey(line.warehouseId(), line.materialId(),
                line.locationId())).distinct().sorted().toList();
        Map<BalanceKey, HotPathMapper.InventoryRow> balances = new HashMap<>();
        for (BalanceKey balanceKey : keys) {
            jdbc.update("""
                    INSERT INTO inventory_balances
                        (id, organization_id, warehouse_id, material_id, location_id, on_hand, reserved, in_transit)
                    VALUES (?, ?, ?, ?, ?, 0, 0, 0) ON CONFLICT DO NOTHING
                    """, UUID.randomUUID(), actor.organizationId(), balanceKey.warehouseId(), balanceKey.materialId(),
                    balanceKey.locationId());
            balances.put(balanceKey, hotPath.lockInventory(actor.organizationId(), balanceKey.warehouseId(),
                    balanceKey.materialId(), balanceKey.locationId()));
        }
        Map<BalanceKey, BigDecimal> received = new HashMap<>();
        lines.forEach(line -> received.merge(new BalanceKey(line.warehouseId(), line.materialId(), line.locationId()),
                line.requested(), BigDecimal::add));
        for (Map.Entry<BalanceKey, BigDecimal> entry : received.entrySet()) {
            HotPathMapper.InventoryRow row = balances.get(entry.getKey());
            InventoryBalance current = new InventoryBalance(row.onHand(), row.reserved(), row.inTransit(), row.version());
            InventoryBalance next = current.receive(entry.getValue());
            if (hotPath.updateInventory(row.id(), next.onHand(), next.reserved(), next.inTransit(), row.version()) != 1) {
                throw BusinessException.conflict("INVENTORY_CONCURRENT_UPDATE", "Inventory changed concurrently");
            }
            jdbc.update("""
                    INSERT INTO inventory_ledger
                        (id, organization_id, inventory_balance_id, reference_type, reference_id,
                         movement_type, quantity, on_hand_after, reserved_after, reason, actor_id)
                    VALUES (?, ?, ?, 'PURCHASE_ORDER', ?, 'RECEIPT', ?, ?, ?, 'Purchase-order receipt', ?)
                    """, UUID.randomUUID(), actor.organizationId(), row.id(), purchaseOrderId, entry.getValue(),
                    next.onHand(), next.reserved(), actor.userId());
        }
        List<PurchaseReceiptResponse.Line> responseLines = new ArrayList<>();
        for (ReceiptLine line : lines) {
            int updated = jdbc.update("""
                    UPDATE purchase_order_lines
                       SET received_quantity = received_quantity + ?, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND version = ? AND received_quantity + ? <= ordered_quantity
                    """, line.requested(), line.id(), line.version(), line.requested());
            if (updated != 1) {
                throw BusinessException.conflict("PURCHASE_RECEIPT_CONFLICT", "Receipt exceeds remaining quantity");
            }
            responseLines.add(new PurchaseReceiptResponse.Line(line.id(), line.materialId(), line.warehouseId(),
                    line.requested()));
        }
        boolean fullyReceived = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT bool_and(received_quantity = ordered_quantity)
                  FROM purchase_order_lines WHERE purchase_order_id = ?
                """, Boolean.class, purchaseOrderId));
        String nextStatus = fullyReceived ? "RECEIVED" : "PARTIALLY_RECEIVED";
        jdbc.update("""
                UPDATE purchase_orders SET status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, nextStatus, purchaseOrderId);
        PurchaseReceiptResponse response = new PurchaseReceiptResponse(purchaseOrderId, nextStatus, responseLines);
        events.write(actor.organizationId(), actor.userId(), "PURCHASE_ORDER", purchaseOrderId, 2,
                "InventoryReceived", Map.of("status", status),
                Map.of("status", nextStatus, "lineCount", responseLines.size()),
                "Purchase-order receipt posted", null, purchaseOrderId);
        idempotency.complete(actor.organizationId(), operation, key, response, 201, purchaseOrderId);
        return response;
    }

    private Suggestion lockSuggestion(UUID id, UUID organizationId) {
        Suggestion value = jdbc.query("""
                SELECT id, material_id, warehouse_id, shortage_quantity, status
                  FROM procurement_suggestions WHERE id = ? AND organization_id = ? FOR UPDATE
                """, resultSet -> resultSet.next() ? new Suggestion(resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class), resultSet.getObject(3, UUID.class),
                        resultSet.getBigDecimal(4), resultSet.getString(5)) : null, id, organizationId);
        if (value == null) throw BusinessException.notFound("Procurement suggestion");
        return value;
    }

    private Template loadTemplate(UUID organizationId) {
        Template value = jdbc.query("""
                SELECT id, template_version, mode, required_approvals, approver_ids::text
                  FROM approval_templates WHERE id = ? AND organization_id = ? AND active
                 ORDER BY template_version DESC LIMIT 1
                """, resultSet -> resultSet.next() ? new Template(resultSet.getObject(1, UUID.class),
                        resultSet.getLong(2), resultSet.getString(3), resultSet.getInt(4),
                        parseUuids(resultSet.getString(5))) : null, PURCHASE_TEMPLATE, organizationId);
        if (value == null) throw BusinessException.notFound("Purchase approval template");
        return value;
    }

    private PurchaseRequestRow lockRequest(UUID id, UUID organizationId) {
        PurchaseRequestRow value = jdbc.query("""
                SELECT request_number, supplier_id, applicant_id, status, total_amount, approval_instance_id
                  FROM purchase_requests WHERE id = ? AND organization_id = ? FOR UPDATE
                """, resultSet -> resultSet.next() ? new PurchaseRequestRow(resultSet.getString(1),
                        resultSet.getObject(2, UUID.class), resultSet.getObject(3, UUID.class),
                        resultSet.getString(4), resultSet.getBigDecimal(5), resultSet.getObject(6, UUID.class)) : null,
                id, organizationId);
        if (value == null) throw BusinessException.notFound("Purchase request");
        return value;
    }

    private Approval lockApproval(UUID id, UUID organizationId) {
        Approval value = jdbc.query("""
                SELECT id, status, resolved_approvers::text FROM approval_instances
                 WHERE id = ? AND organization_id = ? AND object_type = 'PURCHASE_REQUEST' FOR UPDATE
                """, resultSet -> resultSet.next() ? new Approval(resultSet.getObject(1, UUID.class),
                        resultSet.getString(2), parseUuids(resultSet.getString(3))) : null, id, organizationId);
        if (value == null) throw BusinessException.notFound("Purchase approval");
        return value;
    }

    private ReceiptLine loadPurchaseLine(
            ReceivePurchaseOrderRequest.Line request,
            UUID purchaseOrderId,
            UUID organizationId) {
        ReceiptLine value = jdbc.query("""
                SELECT id, material_id, warehouse_id, location_id, ordered_quantity,
                       received_quantity, version
                  FROM purchase_order_lines
                 WHERE id = ? AND purchase_order_id = ? AND organization_id = ? FOR UPDATE
                """, resultSet -> resultSet.next() ? new ReceiptLine(resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class), resultSet.getObject(3, UUID.class),
                        resultSet.getObject(4, UUID.class), resultSet.getBigDecimal(5),
                        resultSet.getBigDecimal(6), resultSet.getLong(7), request.quantity()) : null,
                request.purchaseOrderLineId(), purchaseOrderId, organizationId);
        if (value == null || value.ordered().subtract(value.received()).compareTo(value.requested()) < 0) {
            throw BusinessException.conflict("PURCHASE_RECEIPT_CONFLICT", "Purchase-order line is missing or over-received");
        }
        return value;
    }

    private List<UUID> parseUuids(String value) {
        try {
            List<String> strings = objectMapper.readValue(value,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return strings.stream().map(UUID::fromString).toList();
        } catch (JacksonException exception) {
            throw new IllegalStateException("Invalid approval snapshot", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize approval snapshot", exception);
        }
    }

    private record Suggestion(UUID id, UUID materialId, UUID warehouseId, BigDecimal shortage, String status) {}
    private record Template(UUID id, long version, String mode, int required, List<UUID> approvers) {}
    private record PurchaseRequestRow(String requestNumber, UUID supplierId, UUID applicantId, String status,
                                      BigDecimal totalAmount, UUID approvalId) {}
    private record PurchaseRequestLine(UUID id, UUID materialId, UUID warehouseId, UUID locationId,
                                       BigDecimal quantity, BigDecimal unitCost) {}
    private record Approval(UUID id, String status, List<UUID> approvers) {}
    private record ReceiptLine(UUID id, UUID materialId, UUID warehouseId, UUID locationId, BigDecimal ordered,
                               BigDecimal received, long version, BigDecimal requested) {}
    private record BalanceKey(UUID warehouseId, UUID materialId, UUID locationId)
            implements Comparable<BalanceKey> {
        @Override public int compareTo(BalanceKey other) {
            return Comparator.comparing(BalanceKey::warehouseId).thenComparing(BalanceKey::materialId)
                    .thenComparing(BalanceKey::locationId).compare(this, other);
        }
    }
}
