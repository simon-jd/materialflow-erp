package com.materialflow.erp.billing;

import com.materialflow.erp.audit.AuditOutboxWriter;
import com.materialflow.erp.audit.IdempotencyService;
import com.materialflow.erp.common.BusinessException;
import com.materialflow.identity.ActorContext;
import com.materialflow.sales.OrderStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingApplicationService {
    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final AuditOutboxWriter events;

    public BillingApplicationService(JdbcTemplate jdbc, IdempotencyService idempotency, AuditOutboxWriter events) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.events = events;
    }

    @Transactional
    public InvoiceResponse createInvoice(UUID orderId, String idempotencyKey, ActorContext actor) {
        actor.requireRole("BILLING_WRITE");
        String operation = "billing.invoice." + orderId;
        var requestIdentity = Map.of("orderId", orderId);
        var attempt = idempotency.begin(actor.organizationId(), operation, idempotencyKey,
                requestIdentity, InvoiceResponse.class);
        if (attempt.replay()) {
            return attempt.response();
        }
        OrderRow order = lockOrder(orderId, actor.organizationId());
        if (!(OrderStatus.PARTIALLY_FULFILLED.name().equals(order.status())
                || OrderStatus.FULFILLED.name().equals(order.status())
                || OrderStatus.INVOICED.name().equals(order.status()))) {
            throw BusinessException.conflict("ORDER_NOT_INVOICEABLE", "Only fulfilled quantities can be invoiced");
        }
        List<BillableLine> billable = jdbc.query("""
                SELECT sl.id, sl.order_line_id, sl.quantity, ol.unit_price, ol.discount_rate
                  FROM shipment_lines sl
                  JOIN shipments s ON s.id = sl.shipment_id
                  JOIN sales_order_lines ol ON ol.id = sl.order_line_id
                  LEFT JOIN invoice_lines il ON il.shipment_line_id = sl.id
                 WHERE s.order_id = ? AND s.organization_id = ? AND il.id IS NULL
                 ORDER BY sl.created_at, sl.id
                 FOR UPDATE OF sl, ol
                """, (resultSet, rowNumber) -> new BillableLine(
                        resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class),
                        resultSet.getBigDecimal(3), resultSet.getBigDecimal(4), resultSet.getBigDecimal(5)),
                orderId, actor.organizationId());
        if (billable.isEmpty()) {
            throw BusinessException.conflict("NOTHING_TO_INVOICE", "No uninvoiced shipment quantities exist");
        }

        UUID invoiceId = UUID.randomUUID();
        String invoiceNumber = "INV-" + invoiceId.toString().replace("-", "").substring(0, 20).toUpperCase();
        LocalDate invoiceDate = LocalDate.now(ZoneOffset.UTC);
        LocalDate dueDate = invoiceDate.plusDays(order.paymentTermsDays());
        List<InvoiceResponse.Line> responseLines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (BillableLine line : billable) {
            BigDecimal amount = line.quantity().multiply(line.unitPrice())
                    .multiply(BigDecimal.ONE.subtract(line.discountRate())).setScale(2, RoundingMode.HALF_UP);
            total = total.add(amount);
            responseLines.add(new InvoiceResponse.Line(line.shipmentLineId(), line.orderLineId(), line.quantity(), amount));
        }
        total = total.setScale(2, RoundingMode.HALF_UP);
        jdbc.update("""
                INSERT INTO invoices
                    (id, organization_id, order_id, customer_id, invoice_number, status,
                     total_amount, invoice_date, due_date)
                VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)
                """, invoiceId, actor.organizationId(), orderId, order.customerId(), invoiceNumber,
                total, invoiceDate, dueDate);
        for (int index = 0; index < billable.size(); index++) {
            BillableLine line = billable.get(index);
            InvoiceResponse.Line responseLine = responseLines.get(index);
            jdbc.update("""
                    INSERT INTO invoice_lines
                        (id, organization_id, invoice_id, order_line_id, shipment_line_id,
                         quantity, unit_price, amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), actor.organizationId(), invoiceId, line.orderLineId(),
                    line.shipmentLineId(), line.quantity(), line.unitPrice(), responseLine.amount());
            int updated = jdbc.update("""
                    UPDATE sales_order_lines
                       SET invoiced_quantity = invoiced_quantity + ?, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND invoiced_quantity + ? <= fulfilled_quantity
                    """, line.quantity(), line.orderLineId(), line.quantity());
            if (updated != 1) {
                throw BusinessException.conflict("DUPLICATE_INVOICE_SOURCE", "Fulfillment quantity was already invoiced");
            }
        }

        boolean fullyInvoiced = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT bool_and(ordered_quantity = fulfilled_quantity AND fulfilled_quantity = invoiced_quantity)
                  FROM sales_order_lines WHERE order_id = ?
                """, Boolean.class, orderId));
        if (fullyInvoiced) {
            jdbc.update("""
                    UPDATE sales_orders SET status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND organization_id = ?
                    """, OrderStatus.INVOICED.name(), orderId, actor.organizationId());
        }

        InvoiceResponse response = new InvoiceResponse(invoiceId, orderId, invoiceNumber, "OPEN", total,
                invoiceDate, dueDate, responseLines);
        events.write(actor.organizationId(), actor.userId(), "INVOICE", invoiceId, 1,
                "InvoiceIssued", Map.of(),
                Map.of("invoiceId", invoiceId, "orderId", orderId, "invoiceNumber", invoiceNumber,
                        "totalAmount", total),
                "Invoice created from dispatched shipment lines", null, orderId);
        idempotency.complete(actor.organizationId(), operation, idempotencyKey, response, 201, invoiceId);
        return response;
    }

    private OrderRow lockOrder(UUID orderId, UUID organizationId) {
        OrderRow row = jdbc.query("""
                SELECT o.customer_id, o.status, c.payment_terms_days
                  FROM sales_orders o JOIN customers c ON c.id = o.customer_id
                 WHERE o.id = ? AND o.organization_id = ? FOR UPDATE OF o
                """, resultSet -> resultSet.next()
                        ? new OrderRow(resultSet.getObject(1, UUID.class), resultSet.getString(2), resultSet.getInt(3))
                        : null, orderId, organizationId);
        if (row == null) {
            throw BusinessException.notFound("Sales order");
        }
        return row;
    }

    private record OrderRow(UUID customerId, String status, int paymentTermsDays) {
    }

    private record BillableLine(
            UUID shipmentLineId,
            UUID orderLineId,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discountRate) {
    }
}

