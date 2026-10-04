package com.materialflow.erp.masterdata;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialRepository extends JpaRepository<MaterialEntity, UUID> {
    Optional<MaterialEntity> findByIdAndOrganizationIdAndActiveTrue(UUID id, UUID organizationId);
}

