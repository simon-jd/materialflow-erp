package com.materialflow.erp.order;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.materialflow.credit.CreditAccount;
import com.materialflow.erp.audit.AuditOutboxWriter;
import com.materialflow.erp.audit.IdempotencyService;
import com.materialflow.erp.common.BusinessException;
import com.materialflow.erp.masterdata.CustomerEntity;
import com.materialflow.erp.masterdata.CustomerRepository;
import com.materialflow.erp.masterdata.MaterialEntity;
import com.materialflow.erp.masterdata.MaterialRepository;
import com.materialflow.erp.masterdata.PriceRuleRepository;
import com.materialflow.erp.masterdata.WarehouseEntity;
import com.materialflow.erp.masterdata.WarehouseRepository;
import com.materialflow.erp.persistence.HotPathMapper;
import com.materialflow.identity.ActorContext;
import com.materialflow.inventory.InventoryBalance;
import com.materialflow.pricing.PriceQuote;
import com.materialflow.pricing.PricingRequest;
import com.materialflow.pricing.PricingService;
import com.materialflow.sales.OrderStatus;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderApplicationService {
    private static final String CREATE_OPERATION = "sales-order.create";
    private final CustomerRepository customers;
    private final MaterialRepository materials;
    private final WarehouseRepository warehouses;
    private final PriceRuleRepository priceRules;
    private final PricingService pricing;
    private final HotPathMapper hotPath;
    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final AuditOutboxWriter events;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Duration reservationTtl;
    private final UUID defaultApprovalTemplateId;

    public OrderApplicationService(
            CustomerRepository customers,
            MaterialRepository materials,
            WarehouseRepository warehouses,
            PriceRuleRepository priceRules,
            PricingService pricing,
            HotPathMapper hotPath,
            JdbcTemplate jdbc,
            IdempotencyService idempotency,
            AuditOutboxWriter events,
            ObjectMapper objectMapper,
            Tracer tracer,
            @Value("${materialflow.reservation.ttl:PT2H}") Duration reservationTtl,
            @Value("${materialflow.approval.default-template-id}") UUID defaultApprovalTemplateId) {
        this.customers = customers;
        this.materials = materials;
        this.warehouses = warehouses;
        this.priceRules = priceRules;
        this.pricing = pricing;
        this.hotPath = hotPath;
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.events = events;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.reservationTtl = reservationTtl;
        this.defaultApprovalTemplateId = defaultApprovalTemplateId;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request, String idempotencyKey, ActorContext actor) {
        Span span = tracer.nextSpan()
                .name("sales order database transaction")
                .tag("db.system.name", "postgresql")
                .tag("db.operation.name", "order.create")
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return createInTransaction(request, idempotencyKey, actor);
        } catch (RuntimeException exception) {
            span.error(exception);
            throw exception;
        } finally {
            span.end();
        }
    }

    private OrderResponse createInTransaction(
            CreateOrderRequest request,
            String idempotencyKey,
            ActorContext actor) {
        actor.requireRole("SALES_ORDER_WRITE");
        UUID organizationId = actor.organizationId();
        var attempt = idempotency.begin(organizationId, CREATE_OPERATION, idempotencyKey, request, OrderResponse.class);
        if (attempt.replay()) {
            return attempt.response();
        }

        CustomerEntity customer = customers.findByIdAndOrganizationIdAndActiveTrue(request.customerId(), organizationId)
                .orElseThrow(() -> BusinessException.notFound("Customer"));
        List<PreparedLine> lines = prepareLines(request, customer, actor);
        BigDecimal total = lines.stream().map(line -> line.quote().netAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        ApprovalTemplate template = loadApprovalTemplate(organizationId);
        boolean staticApproval = requiresStaticApproval(lines, total, template);

        HotPathMapper.CreditRow lockedCredit = null;
        boolean creditApproval = false;
        if (!staticApproval) {
            lockedCredit = requireCredit(hotPath.lockCredit(organizationId, request.customerId()));
            var credit = toCredit(lockedCredit);
            creditApproval = credit.frozen() || credit.available().compareTo(total) < 0;
        }
        boolean approvalRequired = staticApproval || creditApproval;
        UUID orderId = UUID.randomUUID();
        UUID approvalId = approvalRequired ? UUID.randomUUID() : null;
        if (approvalId != null && lines.stream().anyMatch(line -> line.request().manualUnitPrice() != null)) {
            lines = prepareLines(request, customer, actor, approvalId);
            total = lines.stream().map(line -> line.quote().netAmount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        }

        if (approvalRequired) {
            insertApproval(approvalId, organizationId, orderId, actor.userId(), template, total, lines,
                    creditApproval);
        }
        String initialStatus = approvalRequired ? OrderStatus.PENDING_APPROVAL.name() : OrderStatus.APPROVED.name();
        insertOrder(orderId, organizationId, request, actor.userId(), total, approvalId, initialStatus);
        insertLines(orderId, organizationId, lines);

        String finalStatus = initialStatus;
        if (!approvalRequired) {
            finalStatus = allocate(orderId, organizationId, request.customerId(), actor.userId(),
                    request.allowPartialFulfillment(), total, lines, lockedCredit, false);
        }

        OrderResponse response = loadResponse(orderId, organizationId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("orderNumber", response.orderNumber());
        payload.put("status", finalStatus);
        payload.put("totalAmount", total);
        payload.put("approvalInstanceId", approvalId);
        events.write(organizationId, actor.userId(), "SALES_ORDER", orderId, response.version(),
                approvalRequired ? "SalesOrderApprovalRequested" : "SalesOrderAllocated",
                Map.of(), payload, "Sales order submitted", null, orderId);
        idempotency.complete(organizationId, CREATE_OPERATION, idempotencyKey, response, 201, orderId);
        return response;
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID orderId, ActorContext actor) {
        actor.requireOrganization(actor.organizationId());
        return loadResponse(orderId, actor.organizationId());
    }

    public String allocateApprovedOrder(UUID orderId, UUID organizationId, UUID actorId, boolean approvedOverride) {
        OrderHeader header = loadHeaderForUpdate(orderId, organizationId);
        if (!OrderStatus.PENDING_APPROVAL.name().equals(header.status())) {
            throw BusinessException.conflict("ORDER_STATE_CONFLICT", "Order is not pending approval");
        }
        jdbc.update("""
                UPDATE sales_orders
                   SET status = 'APPROVED', credit_override_approved = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND organization_id = ? AND status = 'PENDING_APPROVAL'
                """, approvedOverride, orderId, organizationId);
        List<PreparedLine> lines = loadPreparedLines(orderId, organizationId);
        return allocate(orderId, organizationId, header.customerId(), actorId, header.allowPartial(),
                header.totalAmount(), lines, null, approvedOverride);
    }

    public OrderResponse loadResponse(UUID orderId, UUID organizationId) {
        OrderResponse header = jdbc.query("""
                SELECT id, order_number, customer_id, status, total_amount, approval_instance_id, version
                  FROM sales_orders WHERE id = ? AND organization_id = ?
                """, resultSet -> resultSet.next()
                        ? new OrderResponse(resultSet.getObject("id", UUID.class), resultSet.getString("order_number"),
                                resultSet.getObject("customer_id", UUID.class), resultSet.getString("status"),
                                resultSet.getBigDecimal("total_amount"),
                                resultSet.getObject("approval_instance_id", UUID.class), resultSet.getLong("version"), List.of())
                        : null, orderId, organizationId);
        if (header == null) {
            throw BusinessException.notFound("Sales order");
        }
        List<OrderResponse.Line> responseLines = jdbc.query("""
                SELECT id, line_number, material_id, requested_warehouse_id, location_id,
                       ordered_quantity, allocated_quantity, fulfilled_quantity, invoiced_quantity,
                       unit_price, discount_rate, net_amount, price_source, price_rule_version
                  FROM sales_order_lines
                 WHERE order_id = ? AND organization_id = ? ORDER BY line_number
                """, (resultSet, row) -> new OrderResponse.Line(
                        resultSet.getObject("id", UUID.class), resultSet.getInt("line_number"),
                        resultSet.getObject("material_id", UUID.class),
                        resultSet.getObject("requested_warehouse_id", UUID.class),
                        resultSet.getObject("location_id", UUID.class), resultSet.getBigDecimal("ordered_quantity"),
                        resultSet.getBigDecimal("allocated_quantity"), resultSet.getBigDecimal("fulfilled_quantity"),
                        resultSet.getBigDecimal("invoiced_quantity"), resultSet.getBigDecimal("unit_price"),
                        resultSet.getBigDecimal("discount_rate"), resultSet.getBigDecimal("net_amount"),
                        resultSet.getString("price_source"), resultSet.getLong("price_rule_version")),
                orderId, organizationId);
        return new OrderResponse(header.id(), header.orderNumber(), header.customerId(), header.status(),
                header.totalAmount(), header.approvalInstanceId(), header.version(), responseLines);
    }

    private List<PreparedLine> prepareLines(CreateOrderRequest request, CustomerEntity customer, ActorContext actor) {
        return prepareLines(request, customer, actor, null);
    }

    private List<PreparedLine> prepareLines(
            CreateOrderRequest request,
            CustomerEntity customer,
            ActorContext actor,
            UUID approvalReference) {
        List<PreparedLine> result = new ArrayList<>();
        Instant now = Instant.now();
        for (int index = 0; index < request.lines().size(); index++) {
            CreateOrderRequest.Line line = request.lines().get(index);
            if (line.manualDiscountRate() != null && line.manualUnitPrice() == null) {
                throw new BusinessException("INVALID_PRICE_OVERRIDE", HttpStatus.BAD_REQUEST,
                        "A manual discount requires a manual unit price");
            }
            actor.requireWarehouse(line.warehouseId());
            MaterialEntity material = materials.findByIdAndOrganizationIdAndActiveTrue(line.materialId(), actor.organizationId())
                    .orElseThrow(() -> BusinessException.notFound("Material"));
            WarehouseEntity warehouse = warehouses.findByIdAndOrganizationIdAndActiveTrue(line.warehouseId(), actor.organizationId())
                    .orElseThrow(() -> BusinessException.notFound("Warehouse"));
            List<com.materialflow.pricing.PriceRule> rules = priceRules
                    .findByOrganizationIdAndMaterialIdAndActiveTrue(actor.organizationId(), line.materialId())
                    .stream().map(com.materialflow.erp.masterdata.PriceRuleEntity::toDomain).toList();
            PriceQuote quote = pricing.quote(new PricingRequest(line.materialId(), customer.getId(),
                    customer.getRegionCode(), line.quantity(), now, line.manualUnitPrice(), line.manualDiscountRate(),
                    line.overrideReason(), approvalReference), rules);
            result.add(new PreparedLine(UUID.randomUUID(), index + 1, line, material, warehouse, quote));
        }
        return result;
    }

    private boolean requiresStaticApproval(List<PreparedLine> lines, BigDecimal total, ApprovalTemplate template) {
        return total.compareTo(template.amountThreshold()) > 0
                || lines.stream().anyMatch(line -> line.request().manualUnitPrice() != null
                        || line.quote().discountRate().compareTo(template.maxDiscountRate()) > 0);
    }

    private ApprovalTemplate loadApprovalTemplate(UUID organizationId) {
        ApprovalTemplate template = jdbc.query("""
                SELECT id, template_version, mode, required_approvals, amount_threshold,
                       max_discount_rate, approver_ids::text
                  FROM approval_templates
                 WHERE id = ? AND organization_id = ? AND object_type = 'SALES_ORDER' AND active
                 ORDER BY template_version DESC LIMIT 1
                """, resultSet -> resultSet.next() ? approvalTemplate(resultSet) : null,
                defaultApprovalTemplateId, organizationId);
        if (template == null) {
            throw BusinessException.notFound("Active sales-order approval template");
        }
        return template;
    }

    private ApprovalTemplate approvalTemplate(ResultSet resultSet) throws SQLException {
        try {
            List<String> approvers = objectMapper.readValue(resultSet.getString(7),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return new ApprovalTemplate(resultSet.getObject(1, UUID.class), resultSet.getLong(2),
                    resultSet.getString(3), resultSet.getInt(4), resultSet.getBigDecimal(5),
                    resultSet.getBigDecimal(6), approvers.stream().map(UUID::fromString).toList());
        } catch (JacksonException exception) {
            throw new SQLException("Invalid approval template approver snapshot", exception);
        }
    }

    private void insertApproval(
            UUID approvalId,
            UUID organizationId,
            UUID orderId,
            UUID applicantId,
            ApprovalTemplate template,
            BigDecimal total,
            List<PreparedLine> lines,
            boolean creditOverrideRequired) {
        Map<String, Object> inputs = Map.of(
                "totalAmount", total,
                "maximumDiscountRate", lines.stream().map(line -> line.quote().discountRate())
                        .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO),
                "hasManualOverride", lines.stream().anyMatch(line -> line.request().manualUnitPrice() != null),
                "creditOverrideRequired", creditOverrideRequired);
        jdbc.update("""
                INSERT INTO approval_instances
                    (id, organization_id, object_type, object_id, template_id, template_version,
                     applicant_id, status, mode, required_approvals, rule_inputs, resolved_approvers)
                VALUES (?, ?, 'SALES_ORDER', ?, ?, ?, ?, 'PENDING', ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                """, approvalId, organizationId, orderId, template.id(), template.version(), applicantId,
                template.mode(), template.requiredApprovals(), json(inputs), json(template.approvers()));
    }

    private void insertOrder(
            UUID orderId,
            UUID organizationId,
            CreateOrderRequest request,
            UUID applicantId,
            BigDecimal total,
            UUID approvalId,
            String status) {
        String orderNumber = "SO-" + orderId.toString().replace("-", "").substring(0, 20).toUpperCase();
        jdbc.update("""
                INSERT INTO sales_orders
                    (id, organization_id, order_number, customer_id, applicant_id, status,
                     allow_partial_fulfillment, total_amount, approval_instance_id, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """, orderId, organizationId, orderNumber, request.customerId(), applicantId, status,
                request.allowPartialFulfillment(), total, approvalId);
    }

    private void insertLines(UUID orderId, UUID organizationId, List<PreparedLine> lines) {
        for (PreparedLine line : lines) {
            PriceQuote quote = line.quote();
            jdbc.update("""
                    INSERT INTO sales_order_lines
                        (id, organization_id, order_id, line_number, material_id, requested_warehouse_id,
                         location_id, unit, ordered_quantity, unit_price, discount_rate, net_amount,
                         price_source, price_source_id, price_rule_version, override_reason, approval_reference)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, line.id(), organizationId, orderId, line.lineNumber(), line.material().getId(),
                    line.warehouse().getId(), line.request().locationId(), line.material().getBaseUnit().name(),
                    line.request().quantity(), quote.unitPrice(), quote.discountRate(), quote.netAmount(),
                    quote.source().name(), quote.sourceId(), quote.ruleVersion(), quote.overrideReason(),
                    quote.approvalReference());
        }
    }

    private String allocate(
            UUID orderId,
            UUID organizationId,
            UUID customerId,
            UUID actorId,
            boolean allowPartial,
            BigDecimal total,
            List<PreparedLine> lines,
            HotPathMapper.CreditRow alreadyLockedCredit,
            boolean approvedOverride) {
        HotPathMapper.CreditRow creditRow = alreadyLockedCredit == null
                ? requireCredit(hotPath.lockCredit(organizationId, customerId)) : alreadyLockedCredit;
        CreditAccount reservedCredit;
        try {
            reservedCredit = toCredit(creditRow).reserve(total, approvedOverride);
        } catch (CreditAccount.CreditLimitExceededException exception) {
            throw BusinessException.conflict("CREDIT_LIMIT_CONFLICT", exception.getMessage());
        }
        if (hotPath.updateCredit(creditRow.id(), reservedCredit.creditReserved(), reservedCredit.creditUsed(),
                reservedCredit.overdueAmount(), creditRow.version()) != 1) {
            throw BusinessException.conflict("CREDIT_CONCURRENT_UPDATE", "Credit account changed concurrently");
        }
        jdbc.update("""
                INSERT INTO credit_ledger
                    (id, organization_id, credit_account_id, order_id, entry_type, amount,
                     balance_reserved, balance_used, reason, actor_id)
                VALUES (?, ?, ?, ?, 'RESERVE', ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), organizationId, creditRow.id(), orderId, total,
                reservedCredit.creditReserved(), reservedCredit.creditUsed(),
                approvedOverride ? "Approved credit override" : "Sales order reservation", actorId);

        List<PreparedLine> lockOrder = lines.stream().sorted(Comparator
                .comparing((PreparedLine line) -> line.warehouse().getId())
                .thenComparing(line -> line.material().getId())
                .thenComparing(line -> line.request().locationId())).toList();
        Map<UUID, HotPathMapper.InventoryRow> locked = new HashMap<>();
        for (PreparedLine line : lockOrder) {
            HotPathMapper.InventoryRow row = hotPath.lockInventory(organizationId, line.warehouse().getId(),
                    line.material().getId(), line.request().locationId());
            if (row != null) {
                locked.put(line.id(), row);
            }
        }
        boolean allAvailable = lines.stream().allMatch(line -> {
            HotPathMapper.InventoryRow row = locked.get(line.id());
            return row != null && row.onHand().subtract(row.reserved()).compareTo(line.request().quantity()) >= 0;
        });

        BigDecimal totalAllocated = BigDecimal.ZERO;
        for (PreparedLine line : lines) {
            HotPathMapper.InventoryRow row = locked.get(line.id());
            BigDecimal allocated = BigDecimal.ZERO;
            long fencingToken = 0;
            if (row != null && (allowPartial || allAvailable)) {
                InventoryBalance balance = new InventoryBalance(row.onHand(), row.reserved(), row.inTransit(), row.version());
                var result = balance.reserve(line.request().quantity(), allowPartial);
                allocated = result.allocated();
                fencingToken = result.fencingToken();
                if (allocated.signum() > 0) {
                    if (hotPath.updateInventory(row.id(), result.balance().onHand(), result.balance().reserved(),
                            result.balance().inTransit(), row.version()) != 1) {
                        throw BusinessException.conflict("INVENTORY_CONCURRENT_UPDATE", "Inventory changed concurrently");
                    }
                    UUID reservationId = UUID.randomUUID();
                    jdbc.update("""
                            INSERT INTO inventory_reservations
                                (id, organization_id, order_id, order_line_id, inventory_balance_id,
                                 quantity, status, expires_at, fencing_token)
                            VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                            """, reservationId, organizationId, orderId, line.id(), row.id(), allocated,
                            Timestamp.from(Instant.now().plus(reservationTtl)), fencingToken);
                    jdbc.update("""
                            INSERT INTO inventory_ledger
                                (id, organization_id, inventory_balance_id, reference_type, reference_id,
                                 movement_type, quantity, on_hand_after, reserved_after, reason, actor_id)
                            VALUES (?, ?, ?, 'SALES_ORDER', ?, 'RESERVE', ?, ?, ?, 'Sales order allocation', ?)
                            """, UUID.randomUUID(), organizationId, row.id(), orderId, allocated,
                            result.balance().onHand(), result.balance().reserved(), actorId);
                }
            }
            jdbc.update("""
                    UPDATE sales_order_lines SET allocated_quantity = ?, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, allocated, line.id());
            BigDecimal shortage = line.request().quantity().subtract(allocated);
            if (shortage.signum() > 0) {
                jdbc.update("""
                        INSERT INTO procurement_suggestions
                            (id, organization_id, order_id, order_line_id, material_id, warehouse_id, shortage_quantity)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (order_line_id) DO UPDATE
                            SET shortage_quantity = EXCLUDED.shortage_quantity, updated_at = CURRENT_TIMESTAMP
                        """, UUID.randomUUID(), organizationId, orderId, line.id(), line.material().getId(),
                        line.warehouse().getId(), shortage);
            }
            totalAllocated = totalAllocated.add(allocated);
        }

        BigDecimal totalOrdered = lines.stream().map(line -> line.request().quantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String status = totalAllocated.signum() == 0 ? OrderStatus.BACKORDERED.name()
                : totalAllocated.compareTo(totalOrdered) < 0 ? OrderStatus.PARTIALLY_ALLOCATED.name()
                : OrderStatus.RESERVED.name();
        int updated = jdbc.update("""
                UPDATE sales_orders SET status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND organization_id = ? AND status = 'APPROVED'
                """, status, orderId, organizationId);
        if (updated != 1) {
            throw BusinessException.conflict("ORDER_STATE_CONFLICT", "Order state changed while allocating");
        }
        return status;
    }

    private List<PreparedLine> loadPreparedLines(UUID orderId, UUID organizationId) {
        return jdbc.query("""
                SELECT l.id, l.line_number, l.material_id, l.requested_warehouse_id, l.location_id,
                       l.ordered_quantity, l.unit_price, l.discount_rate, l.net_amount, l.price_source,
                       l.price_source_id, l.price_rule_version, l.override_reason, l.approval_reference
                  FROM sales_order_lines l
                 WHERE l.order_id = ? AND l.organization_id = ? ORDER BY l.line_number
                """, (resultSet, row) -> {
                    UUID materialId = resultSet.getObject("material_id", UUID.class);
                    UUID warehouseId = resultSet.getObject("requested_warehouse_id", UUID.class);
                    MaterialEntity material = materials.findByIdAndOrganizationIdAndActiveTrue(materialId, organizationId)
                            .orElseThrow(() -> BusinessException.notFound("Material"));
                    WarehouseEntity warehouse = warehouses.findByIdAndOrganizationIdAndActiveTrue(warehouseId, organizationId)
                            .orElseThrow(() -> BusinessException.notFound("Warehouse"));
                    var request = new CreateOrderRequest.Line(materialId, warehouseId,
                            resultSet.getObject("location_id", UUID.class), resultSet.getBigDecimal("ordered_quantity"),
                            null, null, resultSet.getString("override_reason"));
                    var quote = new PriceQuote(resultSet.getBigDecimal("unit_price"), request.quantity(),
                            resultSet.getBigDecimal("discount_rate"), resultSet.getBigDecimal("net_amount"),
                            com.materialflow.pricing.PriceSource.valueOf(resultSet.getString("price_source")),
                            resultSet.getObject("price_source_id", UUID.class), resultSet.getLong("price_rule_version"),
                            resultSet.getString("override_reason"), resultSet.getObject("approval_reference", UUID.class));
                    return new PreparedLine(resultSet.getObject("id", UUID.class), resultSet.getInt("line_number"),
                            request, material, warehouse, quote);
                }, orderId, organizationId);
    }

    private OrderHeader loadHeaderForUpdate(UUID orderId, UUID organizationId) {
        OrderHeader result = jdbc.query("""
                SELECT customer_id, status, allow_partial_fulfillment, total_amount
                  FROM sales_orders WHERE id = ? AND organization_id = ? FOR UPDATE
                """, resultSet -> resultSet.next() ? new OrderHeader(resultSet.getObject(1, UUID.class),
                        resultSet.getString(2), resultSet.getBoolean(3), resultSet.getBigDecimal(4)) : null,
                orderId, organizationId);
        if (result == null) {
            throw BusinessException.notFound("Sales order");
        }
        return result;
    }

    private HotPathMapper.CreditRow requireCredit(HotPathMapper.CreditRow row) {
        if (row == null) {
            throw BusinessException.notFound("Customer credit account");
        }
        return row;
    }

    private CreditAccount toCredit(HotPathMapper.CreditRow row) {
        return new CreditAccount(row.creditLimit(), row.creditReserved(), row.creditUsed(), row.overdueAmount(),
                row.frozen(), row.version());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize approval snapshot", exception);
        }
    }

    private record PreparedLine(
            UUID id,
            int lineNumber,
            CreateOrderRequest.Line request,
            MaterialEntity material,
            WarehouseEntity warehouse,
            PriceQuote quote) {
    }

    private record ApprovalTemplate(
            UUID id,
            long version,
            String mode,
            int requiredApprovals,
            BigDecimal amountThreshold,
            BigDecimal maxDiscountRate,
            List<UUID> approvers) {
    }

    private record OrderHeader(UUID customerId, String status, boolean allowPartial, BigDecimal totalAmount) {
    }
}
