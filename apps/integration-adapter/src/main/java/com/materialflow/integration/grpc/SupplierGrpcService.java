package com.materialflow.integration.grpc;

import com.materialflow.contracts.grpc.ConfirmPurchaseOrderRequest;
import com.materialflow.contracts.grpc.ConfirmPurchaseOrderResponse;
import com.materialflow.contracts.grpc.SupplierServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

@Component
public class SupplierGrpcService extends SupplierServiceGrpc.SupplierServiceImplBase {
    @Override
    public void confirmPurchaseOrder(
            ConfirmPurchaseOrderRequest request,
            StreamObserver<ConfirmPurchaseOrderResponse> observer) {
        if (request.getRequestId().isBlank() || request.getPurchaseOrderId().isBlank()
                || request.getSupplierId().isBlank()) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("request, purchase order and supplier are required")
                    .asRuntimeException());
            return;
        }
        String compactId = request.getPurchaseOrderId().replace("-", "");
        String confirmation = "SYN-CONF-" + compactId.substring(0, Math.min(compactId.length(), 12)).toUpperCase();
        observer.onNext(ConfirmPurchaseOrderResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setPurchaseOrderId(request.getPurchaseOrderId())
                .setConfirmationCode(confirmation)
                .setStatus("CONFIRMED")
                .setExpectedAt(Instant.now().plus(7, ChronoUnit.DAYS).toString())
                .build());
        observer.onCompleted();
    }
}
