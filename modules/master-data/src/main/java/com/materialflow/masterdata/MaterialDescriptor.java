package com.materialflow.masterdata;

import java.util.UUID;

public record MaterialDescriptor(
        UUID id,
        UUID organizationId,
        String sku,
        String name,
        String specification,
        String grade,
        String color,
        UnitOfMeasure baseUnit) {
}

