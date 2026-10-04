package com.materialflow.erp.procurement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreatePurchaseRequest(
        @NotNull UUID supplierId,
        @NotEmpty @Size(max = 200) List<@Valid Line> lines) {
    public record Line(
            @NotNull UUID suggestionId,
            @NotNull UUID locationId,
            @NotNull @jakarta.validation.constraints.DecimalMin(value = "0", inclusive = false)
            BigDecimal quantity,
            @NotNull @jakarta.validation.constraints.DecimalMin("0") BigDecimal unitCost) {
    }
}

