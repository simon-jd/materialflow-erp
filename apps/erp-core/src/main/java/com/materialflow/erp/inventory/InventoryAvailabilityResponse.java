package com.materialflow.erp.inventory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record InventoryAvailabilityResponse(
        UUID warehouseId,
        String warehouseCode,
        String warehouseName,
        UUID materialId,
        String sku,
        String materialName,
        String baseUnit,
        UUID locationId,
        BigDecimal onHand,
        BigDecimal reserved,
        BigDecimal available,
        BigDecimal inTransit,
        OffsetDateTime updatedAt) {
}
