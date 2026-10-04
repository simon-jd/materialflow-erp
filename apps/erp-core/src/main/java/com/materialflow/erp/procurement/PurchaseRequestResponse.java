package com.materialflow.erp.procurement;

import java.math.BigDecimal;
import java.util.UUID;

public record PurchaseRequestResponse(
        UUID id,
        String requestNumber,
        String status,
        BigDecimal totalAmount,
        UUID approvalInstanceId,
        UUID purchaseOrderId) {
}

