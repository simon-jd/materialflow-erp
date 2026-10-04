package com.materialflow.erp.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(
        @NotNull UUID customerId,
        boolean allowPartialFulfillment,
        @NotEmpty @Size(max = 100) List<@Valid Line> lines) {

    public record Line(
            @NotNull UUID materialId,
            @NotNull UUID warehouseId,
            @NotNull UUID locationId,
            @NotNull @jakarta.validation.constraints.DecimalMin(value = "0", inclusive = false)
            BigDecimal quantity,
            @jakarta.validation.constraints.DecimalMin("0") BigDecimal manualUnitPrice,
            @jakarta.validation.constraints.DecimalMin("0")
            @jakarta.validation.constraints.DecimalMax("1") BigDecimal manualDiscountRate,
            @Size(max = 500) String overrideReason) {
    }
}

