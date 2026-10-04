package com.materialflow.erp.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        UUID orderId,
        String invoiceNumber,
        String status,
        BigDecimal totalAmount,
        LocalDate invoiceDate,
        LocalDate dueDate,
        List<Line> lines) {
    public record Line(UUID shipmentLineId, UUID orderLineId, BigDecimal quantity, BigDecimal amount) {
    }
}

