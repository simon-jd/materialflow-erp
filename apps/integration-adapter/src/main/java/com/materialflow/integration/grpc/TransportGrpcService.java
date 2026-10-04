package com.materialflow.integration.grpc;

import com.materialflow.contracts.grpc.UpdateShipmentStatusRequest;
import com.materialflow.contracts.grpc.UpdateShipmentStatusResponse;
import com.materialflow.contracts.grpc.TransportServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class TransportGrpcService extends TransportServiceGrpc.TransportServiceImplBase {
    private static final Set<String> ACCEPTED = Set.of("PICKED_UP", "IN_TRANSIT", "DELIVERED", "EXCEPTION");

    @Override
    public void updateShipmentStatus(UpdateShipmentStatusRequest request,
            StreamObserver<UpdateShipmentStatusResponse> observer) {
        if (request.getRequestId().isBlank() || request.getShipmentId().isBlank()
                || !ACCEPTED.contains(request.getStatus())) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("invalid shipment status update")
                    .asRuntimeException());
            return;
        }
        observer.onNext(UpdateShipmentStatusResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setShipmentId(request.getShipmentId())
                .setAcceptedStatus(request.getStatus())
                .build());
        observer.onCompleted();
    }
}
