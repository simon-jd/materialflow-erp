package com.materialflow.integration.api;

import com.materialflow.integration.grpc.SupplierIntegrationClient;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/simulators/suppliers")
public class SupplierSimulationController {
    private final SupplierIntegrationClient supplier;

    public SupplierSimulationController(SupplierIntegrationClient supplier) {
        this.supplier = supplier;
    }

    @PostMapping("/purchase-orders/{purchaseOrderId}/confirm")
    public Confirmation confirm(
            @PathVariable UUID purchaseOrderId,
            @RequestParam UUID supplierId) throws Exception {
        var response = supplier.confirm(purchaseOrderId, supplierId).toCompletableFuture().get(4, TimeUnit.SECONDS);
        return new Confirmation(UUID.fromString(response.getPurchaseOrderId()), response.getConfirmationCode(),
                response.getStatus(), response.getExpectedAt());
    }

    public record Confirmation(
            UUID purchaseOrderId,
            String confirmationCode,
            String status,
            String expectedAt) {
    }
}
