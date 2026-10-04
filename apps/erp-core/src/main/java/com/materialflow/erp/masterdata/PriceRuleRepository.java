package com.materialflow.erp.masterdata;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceRuleRepository extends JpaRepository<PriceRuleEntity, UUID> {
    List<PriceRuleEntity> findByOrganizationIdAndMaterialIdAndActiveTrue(UUID organizationId, UUID materialId);
}

