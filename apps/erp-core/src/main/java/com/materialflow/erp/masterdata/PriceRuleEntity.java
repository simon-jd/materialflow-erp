package com.materialflow.erp.masterdata;

import com.materialflow.pricing.PriceRule;
import com.materialflow.pricing.PriceSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "price_rules")
public class PriceRuleEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PriceSource source;
    @Column(name = "material_id", nullable = false)
    private UUID materialId;
    @Column(name = "customer_id")
    private UUID customerId;
    @Column(name = "region_code")
    private String regionCode;
    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;
    @Column(name = "discount_rate", nullable = false)
    private BigDecimal discountRate;
    @Column(name = "rule_version", nullable = false)
    private long ruleVersion;
    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;
    @Column(name = "valid_until")
    private Instant validUntil;
    @Column(nullable = false)
    private boolean active;
    @Version
    private long version;

    protected PriceRuleEntity() {
    }

    public PriceRule toDomain() {
        return new PriceRule(id, source, materialId, customerId, regionCode, unitPrice, discountRate,
                ruleVersion, validFrom, validUntil);
    }
}

