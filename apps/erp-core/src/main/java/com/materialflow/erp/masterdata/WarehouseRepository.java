package com.materialflow.erp.masterdata;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<WarehouseEntity, UUID> {
    Optional<WarehouseEntity> findByIdAndOrganizationIdAndActiveTrue(UUID id, UUID organizationId);
}

