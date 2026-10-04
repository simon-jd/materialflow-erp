package com.materialflow.erp.fulfillment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreateShipmentRequest(@NotEmpty @Size(max = 200) List<@Valid Line> lines) {
    public record Line(
            @NotNull UUID reservationId,
            @NotNull @jakarta.validation.constraints.DecimalMin(value = "0", inclusive = false)
            BigDecimal quantity) {
    }
}

