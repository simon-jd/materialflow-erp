package com.materialflow.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PricingRequest(
        UUID materialId,
        UUID customerId,
        String regionCode,
        BigDecimal quantity,
        Instant at,
        BigDecimal manualUnitPrice,
        BigDecimal manualDiscountRate,
        String overrideReason,
        UUID approvalReference) {

    public PricingRequest {
        if (materialId == null || customerId == null || quantity == null || at == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Material, customer, positive quantity, and time are required");
        }
        if (manualUnitPrice != null && (manualUnitPrice.signum() < 0 || overrideReason == null || overrideReason.isBlank())) {
            throw new IllegalArgumentException("A non-negative manual price requires an override reason");
        }
    }
}

