package com.materialflow.erp.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PaymentFileRequest(
        @NotBlank @Size(max = 255) String fileName,
        @NotEmpty @Size(max = 10000) List<@Valid Record> records) {
    public record Record(
            @NotBlank @Size(max = 120) String externalReference,
            @Size(max = 64) String invoiceReference,
            @Size(max = 64) String orderReference,
            @NotNull @jakarta.validation.constraints.DecimalMin(value = "0", inclusive = false)
            BigDecimal amount,
            @NotNull LocalDate valueDate) {
    }
}

