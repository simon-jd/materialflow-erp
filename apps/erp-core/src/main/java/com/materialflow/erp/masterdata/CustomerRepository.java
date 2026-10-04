package com.materialflow.erp.masterdata;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {
    Optional<CustomerEntity> findByIdAndOrganizationIdAndActiveTrue(UUID id, UUID organizationId);
}

