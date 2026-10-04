package com.materialflow.integration.grpc;

import com.materialflow.contracts.grpc.ConfirmPurchaseOrderRequest;
import com.materialflow.contracts.grpc.ConfirmPurchaseOrderResponse;
import com.materialflow.contracts.grpc.SupplierServiceGrpc;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import io.grpc.ManagedChannel;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

@Service
public class SupplierIntegrationClient {
    private final SupplierServiceGrpc.SupplierServiceBlockingStub supplier;
    private final ExecutorService calls = Executors.newFixedThreadPool(8,
            Thread.ofPlatform().name("supplier-grpc-", 0).factory());

    public SupplierIntegrationClient(ManagedChannel channel) {
        this.supplier = SupplierServiceGrpc.newBlockingStub(channel);
    }

    @Retry(name = "supplier")
    @CircuitBreaker(name = "supplier")
    @Bulkhead(name = "supplier", type = Bulkhead.Type.SEMAPHORE)
    @TimeLimiter(name = "supplier")
    public CompletionStage<ConfirmPurchaseOrderResponse> confirm(UUID purchaseOrderId, UUID supplierId) {
        var request = ConfirmPurchaseOrderRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setPurchaseOrderId(purchaseOrderId.toString())
                .setSupplierId(supplierId.toString())
                .build();
        return CompletableFuture.supplyAsync(() -> supplier.confirmPurchaseOrder(request), calls);
    }

    @PreDestroy
    void close() {
        calls.shutdown();
    }
}
