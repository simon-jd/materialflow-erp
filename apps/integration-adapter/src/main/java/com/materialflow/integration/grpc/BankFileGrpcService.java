package com.materialflow.integration.grpc;

import com.materialflow.contracts.grpc.SubmitSyntheticBankFileRequest;
import com.materialflow.contracts.grpc.SubmitSyntheticBankFileResponse;
import com.materialflow.contracts.grpc.BankFileServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class BankFileGrpcService extends BankFileServiceGrpc.BankFileServiceImplBase {
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    @Override
    public void submitSyntheticBankFile(SubmitSyntheticBankFileRequest request,
            StreamObserver<SubmitSyntheticBankFileResponse> observer) {
        if (request.getRequestId().isBlank() || request.getFileName().isBlank()
                || request.getContent().isEmpty() || request.getContent().size() > MAX_FILE_BYTES) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("bank file is empty or exceeds 5 MiB")
                    .asRuntimeException());
            return;
        }
        try {
            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(request.getContent().toByteArray()));
            observer.onNext(SubmitSyntheticBankFileResponse.newBuilder()
                    .setRequestId(request.getRequestId())
                    .setContentSha256(hash)
                    .setStatus("ACCEPTED_SYNTHETIC")
                    .build());
            observer.onCompleted();
        } catch (NoSuchAlgorithmException exception) {
            observer.onError(Status.INTERNAL.withDescription("SHA-256 is unavailable").asRuntimeException());
        }
    }
}
