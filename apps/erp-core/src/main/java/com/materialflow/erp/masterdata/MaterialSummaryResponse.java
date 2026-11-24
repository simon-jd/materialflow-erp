package com.materialflow.erp.masterdata;

import java.util.UUID;

public record MaterialSummaryResponse(UUID id, String sku, String name, String specification, String baseUnit) {
}
