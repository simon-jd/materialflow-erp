package com.materialflow.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

public final class PricingService {

    public PriceQuote quote(PricingRequest request, List<PriceRule> rules) {
        if (request.manualUnitPrice() != null) {
            var discount = normalizedDiscount(request.manualDiscountRate());
            return snapshot(request.manualUnitPrice(), request.quantity(), discount,
                    PriceSource.MANUAL_OVERRIDE, null, 1L, request.overrideReason(), request.approvalReference());
        }

        PriceRule selected = rules.stream()
                .filter(rule -> rule.matches(request))
                .min(Comparator.comparingInt((PriceRule rule) -> rule.source().priority())
                        .thenComparing(PriceRule::validFrom, Comparator.reverseOrder())
                        .thenComparing(PriceRule::version, Comparator.reverseOrder()))
                .orElseThrow(() -> new PriceNotFoundException(request.materialId()));

        return snapshot(selected.unitPrice(), request.quantity(), selected.discountRate(), selected.source(),
                selected.id(), selected.version(), null, null);
    }

    private PriceQuote snapshot(
            BigDecimal unitPrice,
            BigDecimal quantity,
            BigDecimal discount,
            PriceSource source,
            java.util.UUID sourceId,
            long version,
            String reason,
            java.util.UUID approvalReference) {
        BigDecimal amount = unitPrice.multiply(quantity)
                .multiply(BigDecimal.ONE.subtract(discount))
                .setScale(2, RoundingMode.HALF_UP);
        return new PriceQuote(unitPrice.setScale(4, RoundingMode.HALF_UP), quantity, discount, amount,
                source, sourceId, version, reason, approvalReference);
    }

    private BigDecimal normalizedDiscount(BigDecimal discount) {
        BigDecimal value = discount == null ? BigDecimal.ZERO : discount;
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Discount must be between zero and one");
        }
        return value;
    }

    public static final class PriceNotFoundException extends RuntimeException {
        public PriceNotFoundException(java.util.UUID materialId) {
            super("No applicable price rule for material " + materialId);
        }
    }
}

