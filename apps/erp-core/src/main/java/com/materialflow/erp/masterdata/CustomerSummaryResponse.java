package com.materialflow.erp.masterdata;

import java.util.UUID;

public record CustomerSummaryResponse(UUID id, String code, String name, String regionCode) {
}
