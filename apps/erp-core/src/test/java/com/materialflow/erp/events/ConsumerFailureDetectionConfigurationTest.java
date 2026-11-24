package com.materialflow.erp.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

class ConsumerFailureDetectionConfigurationTest {
    // Loads the real application.yml, so the defaults and env-variable names are under test too.
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ConsumerFailureDetectionConfiguration.class)
            .withPropertyValues("materialflow.kafka.consumer.enabled=true");

    @Test
    void defaultsDetectADeadMemberInTenSecondsWithDynamicMembership() {
        context.run(beans -> assertThat(customizedConfigs(beans))
                .containsEntry(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10_000)
                .containsEntry(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3_000)
                .doesNotContainKey(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG));
    }

    @Test
    void environmentOverridesTimingAndOptsIntoStaticMembership() {
        context.withPropertyValues("MATERIALFLOW_KAFKA_CONSUMER_SESSION_TIMEOUT=45s",
                        "MATERIALFLOW_KAFKA_CONSUMER_HEARTBEAT_INTERVAL=3s",
                        "MATERIALFLOW_KAFKA_CONSUMER_GROUP_INSTANCE_ID=erp-core-0")
                .run(beans -> assertThat(customizedConfigs(beans))
                        .containsEntry(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45_000)
                        .containsEntry(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3_000)
                        .containsEntry(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, "erp-core-0"));
    }

    @Test
    void heartbeatThatDoesNotUndercutTheSessionTimeoutFailsStartup() {
        context.withPropertyValues("MATERIALFLOW_KAFKA_CONSUMER_SESSION_TIMEOUT=3s")
                .run(beans -> assertThat(beans).getFailure().rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("shorter than the session timeout"));
    }

    @Test
    void disabledConsumerLeavesTheFactoryUntouched() {
        context.withPropertyValues("materialflow.kafka.consumer.enabled=false")
                .run(beans -> assertThat(beans).doesNotHaveBean(DefaultKafkaConsumerFactoryCustomizer.class));
    }

    private static Map<String, Object> customizedConfigs(AssertableApplicationContext beans) {
        var factory = new DefaultKafkaConsumerFactory<String, String>(new HashMap<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")));
        beans.getBean(DefaultKafkaConsumerFactoryCustomizer.class).customize(factory);
        return factory.getConfigurationProperties();
    }
}
