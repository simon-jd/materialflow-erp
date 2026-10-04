package com.materialflow.pricing;

import java.math.BigDecimal;
import java.util.UUID;

public record PriceQuote(
        BigDecimal unitPrice,
        BigDecimal quantity,
        BigDecimal discountRate,
        BigDecimal netAmount,
        PriceSource source,
        UUID sourceId,
        long ruleVersion,
        String overrideReason,
        UUID approvalReference) {
}

