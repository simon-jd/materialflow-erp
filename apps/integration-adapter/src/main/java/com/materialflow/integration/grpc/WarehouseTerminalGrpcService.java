package com.materialflow.integration.grpc;

import com.materialflow.contracts.grpc.BatchSyncInventoryRequest;
import com.materialflow.contracts.grpc.BatchSyncInventoryResponse;
import com.materialflow.contracts.grpc.WarehouseTerminalServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class WarehouseTerminalGrpcService extends WarehouseTerminalServiceGrpc.WarehouseTerminalServiceImplBase {
    @Override
    public void batchSyncInventory(
            BatchSyncInventoryRequest request,
            StreamObserver<BatchSyncInventoryResponse> observer) {
        try {
            if (request.getRequestId().isBlank() || request.getPositionsCount() == 0) {
                throw new IllegalArgumentException("request_id and positions are required");
            }
            request.getPositionsList().forEach(position -> {
                if (position.getOrganizationId().isBlank() || position.getWarehouseId().isBlank()
                        || position.getMaterialId().isBlank() || position.getLocationId().isBlank()
                        || new BigDecimal(position.getOnHand()).signum() < 0 || position.getSourceVersion() < 0) {
                    throw new IllegalArgumentException("invalid inventory position");
                }
            });
            observer.onNext(BatchSyncInventoryResponse.newBuilder()
                    .setRequestId(request.getRequestId())
                    .setAcceptedCount(request.getPositionsCount())
                    .setStatus("ACCEPTED_SYNTHETIC")
                    .build());
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(exception.getMessage()).asRuntimeException());
        }
    }
}
