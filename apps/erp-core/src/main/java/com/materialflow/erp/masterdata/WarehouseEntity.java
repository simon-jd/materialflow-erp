package com.materialflow.erp.masterdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "warehouses")
public class WarehouseEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(nullable = false)
    private String code;
    @Column(nullable = false)
    private String name;
    @Column(name = "region_code", nullable = false)
    private String regionCode;
    @Column(nullable = false)
    private boolean active;
    @Version
    private long version;

    protected WarehouseEntity() {
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getRegionCode() { return regionCode; }
    public boolean isActive() { return active; }
}

