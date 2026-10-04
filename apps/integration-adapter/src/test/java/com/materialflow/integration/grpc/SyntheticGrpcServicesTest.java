package com.materialflow.integration.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.materialflow.contracts.grpc.SubmitSyntheticBankFileRequest;
import com.materialflow.contracts.grpc.SubmitSyntheticBankFileResponse;
import com.materialflow.contracts.grpc.BatchSyncInventoryRequest;
import com.materialflow.contracts.grpc.BatchSyncInventoryResponse;
import com.materialflow.contracts.grpc.InventoryPosition;
import com.materialflow.contracts.grpc.ConfirmPurchaseOrderRequest;
import com.materialflow.contracts.grpc.ConfirmPurchaseOrderResponse;
import com.materialflow.contracts.grpc.UpdateShipmentStatusRequest;
import com.materialflow.contracts.grpc.UpdateShipmentStatusResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SyntheticGrpcServicesTest {
    @Test
    void servicesAcceptBoundedSyntheticContracts() {
        var warehouse = new CapturingObserver<BatchSyncInventoryResponse>();
        new WarehouseTerminalGrpcService().batchSyncInventory(BatchSyncInventoryRequest.newBuilder()
                .setRequestId("inventory-1")
                .addPositions(InventoryPosition.newBuilder()
                        .setOrganizationId("org-1").setWarehouseId("warehouse-1")
                        .setMaterialId("material-1").setLocationId("location-1")
                        .setOnHand("12.500000").setSourceVersion(7))
                .build(), warehouse);
        assertThat(warehouse.value.getAcceptedCount()).isEqualTo(1);
        assertThat(warehouse.completed).isTrue();

        var supplier = new CapturingObserver<ConfirmPurchaseOrderResponse>();
        new SupplierGrpcService().confirmPurchaseOrder(ConfirmPurchaseOrderRequest.newBuilder()
                .setRequestId("supplier-1").setPurchaseOrderId("purchase-order-123456")
                .setSupplierId("supplier-1").build(), supplier);
        assertThat(supplier.value.getConfirmationCode()).startsWith("SYN-CONF-");
        assertThat(supplier.value.getStatus()).isEqualTo("CONFIRMED");

        var transport = new CapturingObserver<UpdateShipmentStatusResponse>();
        new TransportGrpcService().updateShipmentStatus(UpdateShipmentStatusRequest.newBuilder()
                .setRequestId("transport-1").setShipmentId("shipment-1").setStatus("DELIVERED").build(),
                transport);
        assertThat(transport.value.getAcceptedStatus()).isEqualTo("DELIVERED");

        var bank = new CapturingObserver<SubmitSyntheticBankFileResponse>();
        new BankFileGrpcService().submitSyntheticBankFile(SubmitSyntheticBankFileRequest.newBuilder()
                .setRequestId("bank-1").setFileName("synthetic.csv")
                .setContent(ByteString.copyFrom("invoice,amount\nINV-1,10.00", StandardCharsets.UTF_8)).build(),
                bank);
        assertThat(bank.value.getContentSha256()).hasSize(64);
        assertThat(bank.value.getStatus()).isEqualTo("ACCEPTED_SYNTHETIC");
    }

    @Test
    void malformedAndRegressiveInputsFailWithInvalidArgument() {
        var inventory = new CapturingObserver<BatchSyncInventoryResponse>();
        new WarehouseTerminalGrpcService().batchSyncInventory(BatchSyncInventoryRequest.newBuilder()
                .setRequestId("inventory-invalid")
                .addPositions(InventoryPosition.newBuilder().setOrganizationId("org")
                        .setWarehouseId("warehouse").setMaterialId("material").setLocationId("location")
                        .setOnHand("-1").setSourceVersion(1)).build(), inventory);
        assertInvalidArgument(inventory.error);

        var shipment = new CapturingObserver<UpdateShipmentStatusResponse>();
        new TransportGrpcService().updateShipmentStatus(UpdateShipmentStatusRequest.newBuilder()
                .setRequestId("shipment-invalid").setShipmentId("shipment").setStatus("CREATED").build(),
                shipment);
        assertInvalidArgument(shipment.error);

        var bank = new CapturingObserver<SubmitSyntheticBankFileResponse>();
        new BankFileGrpcService().submitSyntheticBankFile(SubmitSyntheticBankFileRequest.newBuilder()
                .setRequestId("empty").setFileName("empty.csv").build(), bank);
        assertInvalidArgument(bank.error);
    }

    private void assertInvalidArgument(Throwable error) {
        assertThat(error).isInstanceOfSatisfying(StatusRuntimeException.class,
                exception -> assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    private static final class CapturingObserver<T> implements StreamObserver<T> {
        private T value;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T response) {
            value = response;
        }

        @Override
        public void onError(Throwable failure) {
            error = failure;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
