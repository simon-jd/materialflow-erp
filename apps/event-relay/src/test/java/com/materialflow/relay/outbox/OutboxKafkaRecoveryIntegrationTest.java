package com.materialflow.relay.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;

class OutboxKafkaRecoveryIntegrationTest extends OutboxPostgresFixture {
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2");

    @Test
    void brokerAckThenDatabaseFailureReplaysTheSameEventAtADifferentOffset() throws Exception {
        String topic = "relay-recovery-" + UUID.randomUUID();
        UUID id = insertEvent(topic);
        var disconnectedAfterAck = new OutboxRepository(jdbc) {
            @Override
            public boolean markPublished(UUID eventId, String instanceId, UUID token) {
                throw new DataAccessResourceFailureException("injected DB failure after real Kafka ACK");
            }
        };
        var environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new YamlPropertySourceLoader()
                .load("relay-runtime", new ClassPathResource("application.yml")).getFirst());
        var runtime = Binder.get(environment).bind("spring.kafka", KafkaProperties.class).get();
        var producerConfig = runtime.buildProducerProperties();
        producerConfig.put("bootstrap.servers", KAFKA.getBootstrapServers());
        // Exercise production settings: Kafka 4 changed linger's default and exposed an invalid timeout pair.
        var factory = new DefaultKafkaProducerFactory<String, String>(producerConfig);
        var properties = new RelayProperties("reused-hostname", 1, 3, LEASE,
                Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(4));
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(), "group.id", "verify-" + id,
                "auto.offset.reset", "earliest", "enable.auto.commit", false,
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class))) {
            var kafka = new KafkaTemplate<>(factory);
            assertThatThrownBy(() -> new OutboxRelay(disconnectedAfterAck, kafka, properties,
                    new SimpleMeterRegistry(), Tracer.NOOP, Propagator.NOOP).publishBatch())
                    .isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(status(id)).isEqualTo("CLAIMED");
            expire(id); // Deterministic lease-clock injection, not a process-kill or wall-clock recovery benchmark.
            new OutboxRelay(repository, kafka, properties, new SimpleMeterRegistry(),
                    Tracer.NOOP, Propagator.NOOP).publishBatch();
            assertThat(status(id)).isEqualTo("PUBLISHED");
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (received.size() < 2 && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(received::add);
            }
            assertThat(received).hasSize(2);
            assertThat(received.get(0).key()).isEqualTo(id.toString());
            assertThat(received.get(1).key()).isEqualTo(received.get(0).key());
            assertThat(received.get(1).value()).isEqualTo(received.get(0).value());
            assertThat(received.get(1).offset()).isGreaterThan(received.get(0).offset());
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_events WHERE id = ?",
                    Integer.class, id)).isEqualTo(2);
        } finally {
            factory.destroy();
        }
    }
}
