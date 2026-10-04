package com.materialflow.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PricingServiceTest {
    private final PricingService service = new PricingService();

    @Test
    void selectsContractBeforeCustomerRegionAndStandard() {
        UUID customer = UUID.randomUUID();
        UUID material = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        var rules = List.of(
                rule(PriceSource.STANDARD, material, null, null, "15.00", now),
                rule(PriceSource.REGIONAL, material, null, "WEST", "14.00", now),
                rule(PriceSource.CUSTOMER_SPECIAL, material, customer, null, "13.00", now),
                rule(PriceSource.CONTRACT, material, customer, null, "12.00", now));

        var quote = service.quote(new PricingRequest(material, customer, "WEST", new BigDecimal("10"),
                now.plusSeconds(1), null, null, null, null), rules);

        assertThat(quote.source()).isEqualTo(PriceSource.CONTRACT);
        assertThat(quote.unitPrice()).isEqualByComparingTo("12.0000");
        assertThat(quote.netAmount()).isEqualByComparingTo("120.00");
    }

    @Test
    void snapshotUsesDecimalArithmeticAndDoesNotReferenceMutableRuleState() {
        UUID customer = UUID.randomUUID();
        UUID material = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        var quote = service.quote(new PricingRequest(material, customer, "WEST", new BigDecimal("3.333"),
                now, null, null, null, null),
                List.of(new PriceRule(UUID.randomUUID(), PriceSource.STANDARD, material, null, null,
                        new BigDecimal("9.9999"), new BigDecimal("0.075"), 7, now, null)));

        assertThat(quote.netAmount()).isEqualByComparingTo("30.83");
        assertThat(quote.ruleVersion()).isEqualTo(7);
    }

    private PriceRule rule(
            PriceSource source,
            UUID material,
            UUID customer,
            String region,
            String price,
            Instant validFrom) {
        return new PriceRule(UUID.randomUUID(), source, material, customer, region,
                new BigDecimal(price), BigDecimal.ZERO, 1, validFrom, null);
    }
}

