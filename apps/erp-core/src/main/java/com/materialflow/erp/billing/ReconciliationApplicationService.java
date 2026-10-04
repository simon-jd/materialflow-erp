package com.materialflow.erp.billing;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.materialflow.credit.CreditAccount;
import com.materialflow.erp.audit.AuditOutboxWriter;
import com.materialflow.erp.audit.IdempotencyService;
import com.materialflow.erp.common.BusinessException;
import com.materialflow.erp.persistence.HotPathMapper;
import com.materialflow.identity.ActorContext;
import com.materialflow.sales.OrderStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationApplicationService {
    private final JdbcTemplate jdbc;
    private final HotPathMapper hotPath;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotency;
    private final AuditOutboxWriter events;

    public ReconciliationApplicationService(
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
    public PaymentImportResponse importFile(PaymentFileRequest request, String idempotencyKey, ActorContext actor) {
        actor.requireRole("RECONCILIATION_WRITE");
        String operation = "reconciliation.payment-file";
        var attempt = idempotency.begin(actor.organizationId(), operation, idempotencyKey,
                request, PaymentImportResponse.class);
        if (attempt.replay()) {
            return attempt.response();
        }
        UUID fileId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_files
                    (id, organization_id, file_name, content_hash, record_count, imported_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, fileId, actor.organizationId(), request.fileName(), sha256(request), request.records().size(),
                actor.userId());

        int matched = 0;
        int exceptions = 0;
        int duplicates = 0;
        for (int index = 0; index < request.records().size(); index++) {
            PaymentFileRequest.Record record = request.records().get(index);
            Long duplicate = jdbc.queryForObject("""
                    SELECT count(*) FROM bank_transactions
                     WHERE organization_id = ? AND external_reference = ?
                    """, Long.class, actor.organizationId(), record.externalReference());
            if (duplicate != null && duplicate > 0) {
                duplicates++;
                continue;
            }
            UUID transactionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO bank_transactions
                        (id, organization_id, payment_file_id, line_number, external_reference,
                         invoice_reference, order_reference, amount, value_date, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'IMPORTED')
                    """, transactionId, actor.organizationId(), fileId, index + 1, record.externalReference(),
                    blankToNull(record.invoiceReference()), blankToNull(record.orderReference()),
                    record.amount(), record.valueDate());

            List<InvoiceCandidate> candidates = findCandidates(record, actor.organizationId());
            if (candidates.size() != 1) {
                String reason = candidates.isEmpty() ? "UNMATCHED" : "AMBIGUOUS";
                exception(transactionId, actor.organizationId(), reason,
                        candidates.isEmpty() ? "No open invoice matched the supplied references"
                                : "More than one open invoice matched the supplied references");
                exceptions++;
                continue;
            }
            InvoiceCandidate candidate = candidates.getFirst();
            HotPathMapper.CreditRow creditRow = hotPath.lockCredit(actor.organizationId(), candidate.customerId());
            InvoiceCandidate invoice = lockInvoice(candidate.invoiceId(), actor.organizationId());
            var outstanding = invoice.totalAmount().subtract(invoice.paidAmount());
            if (record.amount().compareTo(outstanding) > 0) {
                exception(transactionId, actor.organizationId(), "AMOUNT_MISMATCH",
                        "Payment amount exceeds the invoice outstanding amount");
                exceptions++;
                continue;
            }
            if (creditRow == null) {
                exception(transactionId, actor.organizationId(), "CREDIT_ACCOUNT_MISSING",
                        "The matched customer has no credit account");
                exceptions++;
                continue;
            }
            CreditAccount settled;
            try {
                settled = new CreditAccount(creditRow.creditLimit(), creditRow.creditReserved(), creditRow.creditUsed(),
                        creditRow.overdueAmount(), creditRow.frozen(), creditRow.version()).settle(record.amount());
            } catch (RuntimeException exception) {
                exception(transactionId, actor.organizationId(), "CREDIT_BALANCE_MISMATCH",
                        "Payment exceeds the captured customer credit balance");
                exceptions++;
                continue;
            }
            if (hotPath.updateCredit(creditRow.id(), settled.creditReserved(), settled.creditUsed(),
                    settled.overdueAmount(), creditRow.version()) != 1) {
                throw BusinessException.conflict("CREDIT_CONCURRENT_UPDATE", "Credit changed concurrently");
            }
            jdbc.update("""
                    INSERT INTO credit_ledger
                        (id, organization_id, credit_account_id, order_id, entry_type, amount,
                         balance_reserved, balance_used, reason, actor_id)
                    VALUES (?, ?, ?, ?, 'SETTLE', ?, ?, ?, 'Matched synthetic bank transaction', ?)
                    """, UUID.randomUUID(), actor.organizationId(), creditRow.id(), invoice.orderId(), record.amount(),
                    settled.creditReserved(), settled.creditUsed(), actor.userId());
            jdbc.update("""
                    INSERT INTO payment_allocations
                        (id, organization_id, bank_transaction_id, invoice_id, amount)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), actor.organizationId(), transactionId, invoice.invoiceId(), record.amount());
            var paidAfter = invoice.paidAmount().add(record.amount());
            String invoiceStatus = paidAfter.compareTo(invoice.totalAmount()) == 0 ? "PAID" : "PARTIALLY_PAID";
            jdbc.update("""
                    UPDATE invoices SET paid_amount = ?, status = ?, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, paidAfter, invoiceStatus, invoice.invoiceId());
            jdbc.update("UPDATE bank_transactions SET status = 'MATCHED' WHERE id = ?", transactionId);
            if ("PAID".equals(invoiceStatus) && allInvoicesPaid(invoice.orderId())) {
                jdbc.update("""
                        UPDATE sales_orders SET status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                         WHERE id = ? AND organization_id = ? AND status = ?
                        """, OrderStatus.SETTLED.name(), invoice.orderId(), actor.organizationId(),
                        OrderStatus.INVOICED.name());
            }
            matched++;
        }

        PaymentImportResponse response = new PaymentImportResponse(fileId, request.records().size(),
                matched, exceptions, duplicates);
        events.write(actor.organizationId(), actor.userId(), "PAYMENT_FILE", fileId, 1,
                "PaymentFileImported", Map.of(),
                Map.of("fileId", fileId, "recordCount", request.records().size(), "matchedCount", matched,
                        "exceptionCount", exceptions, "duplicateCount", duplicates),
                "Synthetic bank file import", null, fileId);
        idempotency.complete(actor.organizationId(), operation, idempotencyKey, response, 201, fileId);
        return response;
    }

    private List<InvoiceCandidate> findCandidates(PaymentFileRequest.Record record, UUID organizationId) {
        if (record.invoiceReference() != null && !record.invoiceReference().isBlank()) {
            return jdbc.query("""
                    SELECT id, order_id, customer_id, total_amount, paid_amount
                      FROM invoices
                     WHERE organization_id = ? AND invoice_number = ? AND status IN ('OPEN', 'PARTIALLY_PAID')
                    """, (resultSet, row) -> candidate(resultSet), organizationId, record.invoiceReference());
        }
        if (record.orderReference() != null && !record.orderReference().isBlank()) {
            return jdbc.query("""
                    SELECT i.id, i.order_id, i.customer_id, i.total_amount, i.paid_amount
                      FROM invoices i JOIN sales_orders o ON o.id = i.order_id
                     WHERE i.organization_id = ? AND o.order_number = ?
                       AND i.status IN ('OPEN', 'PARTIALLY_PAID')
                    """, (resultSet, row) -> candidate(resultSet), organizationId, record.orderReference());
        }
        return List.of();
    }

    private InvoiceCandidate lockInvoice(UUID invoiceId, UUID organizationId) {
        InvoiceCandidate candidate = jdbc.query("""
                SELECT id, order_id, customer_id, total_amount, paid_amount
                  FROM invoices WHERE id = ? AND organization_id = ? FOR UPDATE
                """, resultSet -> resultSet.next() ? candidate(resultSet) : null, invoiceId, organizationId);
        if (candidate == null) {
            throw BusinessException.notFound("Invoice");
        }
        return candidate;
    }

    private InvoiceCandidate candidate(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new InvoiceCandidate(resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class),
                resultSet.getObject(3, UUID.class), resultSet.getBigDecimal(4), resultSet.getBigDecimal(5));
    }

    private void exception(UUID transactionId, UUID organizationId, String code, String detail) {
        jdbc.update("""
                INSERT INTO reconciliation_exceptions
                    (id, organization_id, bank_transaction_id, reason_code, detail)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), organizationId, transactionId, code, detail);
        jdbc.update("UPDATE bank_transactions SET status = 'EXCEPTION' WHERE id = ?", transactionId);
    }

    private boolean allInvoicesPaid(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT bool_and(status = 'PAID') FROM invoices WHERE order_id = ?
                """, Boolean.class, orderId));
    }

    private String sha256(Object value) {
        try {
            byte[] json = objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JacksonException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not hash payment file", exception);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record InvoiceCandidate(
            UUID invoiceId,
            UUID orderId,
            UUID customerId,
            java.math.BigDecimal totalAmount,
            java.math.BigDecimal paidAmount) {
    }
}
