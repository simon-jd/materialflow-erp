package com.materialflow.erp.order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record OrderSummaryResponse(
        UUID id,
        String orderNumber,
        UUID customerId,
        String customerName,
        String status,
        BigDecimal totalAmount,
        String currency,
        UUID approvalInstanceId,
        OffsetDateTime createdAt,
        long version) {
}
