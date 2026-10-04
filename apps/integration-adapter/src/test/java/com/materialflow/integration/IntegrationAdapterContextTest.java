package com.materialflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.materialflow.integration.grpc.GrpcServerLifecycle;
import com.materialflow.integration.grpc.SupplierIntegrationClient;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "server.port=0",
        "materialflow.grpc.port=0",
        "management.tracing.enabled=false"
})
class IntegrationAdapterContextTest {
    @Autowired GrpcServerLifecycle grpcServer;
    @Autowired SupplierIntegrationClient supplier;
    @Autowired RetryRegistry retries;
    @Autowired BulkheadRegistry bulkheads;

    @Test
    void fullSpringContextAndGrpcServerStartTogether() {
        assertThat(grpcServer.isRunning()).isTrue();
    }

    @Test
    void boot4LoadsConfiguredResilienceAspects() {
        assertThat(AopUtils.isAopProxy(supplier)).isTrue();
        assertThat(retries.retry("supplier").getRetryConfig().getMaxAttempts()).isEqualTo(3);
        assertThat(bulkheads.bulkhead("supplier").getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(8);
    }
}
