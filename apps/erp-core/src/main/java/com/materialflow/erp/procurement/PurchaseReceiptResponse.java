package com.materialflow.erp.procurement;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PurchaseReceiptResponse(UUID purchaseOrderId, String status, List<Line> lines) {
    public record Line(UUID purchaseOrderLineId, UUID materialId, UUID warehouseId, BigDecimal receivedQuantity) {
    }
}

