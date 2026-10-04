package com.materialflow.erp.fulfillment;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ShipmentResponse(
        UUID id,
        UUID orderId,
        String shipmentNumber,
        String status,
        BigDecimal capturedCredit,
        List<Line> lines) {
    public record Line(UUID reservationId, UUID orderLineId, BigDecimal quantity) {
    }
}

