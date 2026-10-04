package com.materialflow.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PriceRule(
        UUID id,
        PriceSource source,
        UUID materialId,
        UUID customerId,
        String regionCode,
        BigDecimal unitPrice,
        BigDecimal discountRate,
        long version,
        Instant validFrom,
        Instant validUntil) {

    public PriceRule {
        if (id == null || source == null || materialId == null || unitPrice == null || validFrom == null) {
            throw new IllegalArgumentException("Required price-rule field is missing");
        }
        if (unitPrice.signum() < 0 || version < 1) {
            throw new IllegalArgumentException("Price and version must be non-negative/positive");
        }
        discountRate = discountRate == null ? BigDecimal.ZERO : discountRate;
        if (discountRate.signum() < 0 || discountRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("discountRate must be between zero and one");
        }
    }

    public boolean matches(PricingRequest request) {
        if (!materialId.equals(request.materialId()) || validFrom.isAfter(request.at())) {
            return false;
        }
        if (validUntil != null && !request.at().isBefore(validUntil)) {
            return false;
        }
        return switch (source) {
            case CONTRACT, CUSTOMER_SPECIAL -> customerId != null && customerId.equals(request.customerId());
            case REGIONAL -> regionCode != null && regionCode.equals(request.regionCode());
            case STANDARD -> true;
            case MANUAL_OVERRIDE -> false;
        };
    }
}

