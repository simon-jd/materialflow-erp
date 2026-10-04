package com.materialflow.erp.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String orderNumber,
        UUID customerId,
        String status,
        BigDecimal totalAmount,
        UUID approvalInstanceId,
        long version,
        List<Line> lines) {

    public record Line(
            UUID id,
            int lineNumber,
            UUID materialId,
            UUID warehouseId,
            UUID locationId,
            BigDecimal orderedQuantity,
            BigDecimal allocatedQuantity,
            BigDecimal fulfilledQuantity,
            BigDecimal invoicedQuantity,
            BigDecimal unitPrice,
            BigDecimal discountRate,
            BigDecimal netAmount,
            String priceSource,
            long priceRuleVersion) {
    }
}

