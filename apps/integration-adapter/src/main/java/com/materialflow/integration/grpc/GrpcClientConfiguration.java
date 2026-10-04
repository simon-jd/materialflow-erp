package com.materialflow.integration.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcClientConfiguration {
    @Bean(destroyMethod = "shutdown")
    ManagedChannel syntheticChannel(
            @Value("${materialflow.grpc.client.host:localhost}") String host,
            @Value("${materialflow.grpc.client.port:${materialflow.grpc.port:9090}}") int port) {
        return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    }
}
