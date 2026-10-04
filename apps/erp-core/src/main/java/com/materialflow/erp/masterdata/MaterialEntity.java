package com.materialflow.erp.masterdata;

import com.materialflow.masterdata.UnitOfMeasure;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "materials")
public class MaterialEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(nullable = false)
    private String sku;
    @Column(nullable = false)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(name = "base_unit", nullable = false)
    private UnitOfMeasure baseUnit;
    @Column(nullable = false)
    private boolean active;
    @Version
    private long version;

    protected MaterialEntity() {
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public UnitOfMeasure getBaseUnit() { return baseUnit; }
    public boolean isActive() { return active; }
}

