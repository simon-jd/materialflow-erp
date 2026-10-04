package com.materialflow.integration.grpc;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class GrpcServerLifecycle implements SmartLifecycle {
    private final List<BindableService> services;
    private final int port;
    private volatile boolean running;
    private Server server;

    public GrpcServerLifecycle(List<BindableService> services, @Value("${materialflow.grpc.port:9090}") int port) {
        this.services = services;
        this.port = port;
    }

    @Override
    public void start() {
        ServerBuilder<?> builder = ServerBuilder.forPort(port);
        services.forEach(builder::addService);
        try {
            server = builder.build().start();
            running = true;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start synthetic gRPC server", exception);
        }
    }

    @Override
    public void stop() {
        if (server != null) server.shutdown();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
