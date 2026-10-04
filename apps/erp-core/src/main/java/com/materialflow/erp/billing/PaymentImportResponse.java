package com.materialflow.erp.billing;

import java.util.UUID;

public record PaymentImportResponse(
        UUID fileId,
        int recordCount,
        int matchedCount,
        int exceptionCount,
        int duplicateCount) {
}

