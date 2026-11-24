package com.materialflow.erp.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import java.util.HashMap;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@SpringBootTest(properties = {"materialflow.kafka.consumer.enabled=false",
        "materialflow.security.enabled=false", "materialflow.reservation.expiry-enabled=false"})
class DomainEventConsumerRecoveryIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withCommand("postgres", "-c", "lock_timeout=200ms");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired DomainEventInboxApplicationService inbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ConsumerFactory<Object, Object> configuredConsumerFactory;

    @Test
    void newConsumerGroupReplaysEventsPublishedBeforeItStarts() throws Exception {
        try (var scenario = new Scenario(true, false)) {
            UUID eventId = UUID.randomUUID();
            scenario.send(envelope(eventId));
            scenario.start();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(inboxCount(eventId)).isEqualTo(1);
                assertThat(scenario.committedOffset()).isEqualTo(1);
            });
        }
    }

    @Test
    void databaseLockOutlivesOldRetryBudgetWithoutDeadLetterOrOffsetAdvance() throws Exception {
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("200ms");
        try (var scenario = new Scenario(true); Connection blocker = dataSource.getConnection()) {
            UUID eventId = UUID.randomUUID();
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("LOCK TABLE consumer_inbox IN ACCESS EXCLUSIVE MODE");
            }
            scenario.send(envelope(eventId));
            try {
                await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                        assertThat(scenario.count("event_consumer_failure_total", "database") >= 6
                                || scenario.count("event_consumer_recovered_total", "database") > 0
                                || scenario.count("event_consumer_recovered_total", "other") > 0)
                                .withFailMessage("Database failures=%s, other failures=%s, recovered=%s, last failure=%s",
                                        scenario.count("event_consumer_failure_total", "database"),
                                        scenario.count("event_consumer_failure_total", "other"),
                                        scenario.count("event_consumer_recovered_total", "other"),
                                        scenario.lastFailure.get())
                                .isTrue());
                assertThat(scenario.count("event_consumer_failure_total", "database"))
                        .withFailMessage("Last listener failure: %s", scenario.lastFailure.get())
                        .isGreaterThanOrEqualTo(6);
                assertThat(scenario.count("event_consumer_recovered_total", "database")).isZero();
                assertThat(scenario.committedOffset()).isLessThanOrEqualTo(0);
                assertThat(scenario.dlqRecords(Duration.ofMillis(500))).isEmpty();
            } finally {
                blocker.rollback();
            }
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(inboxCount(eventId)).isEqualTo(1);
                assertThat(scenario.committedOffset()).isEqualTo(1);
            });
            assertThat(scenario.dlqRecords(Duration.ofMillis(500))).isEmpty();
        }
    }

    @Test
    void poisonIsQuarantinedOnceAndTheFollowingValidRecordCommits() throws Exception {
        try (var scenario = new Scenario(true)) {
            UUID eventId = UUID.randomUUID();
            scenario.send("not-json");
            scenario.send(envelope(eventId));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(scenario.committedOffset()).isEqualTo(2);
                assertThat(inboxCount(eventId)).isEqualTo(1);
            });
            var records = scenario.dlqRecords(Duration.ofSeconds(2));
            assertThat(records).hasSize(1);
            var record = records.getFirst();
            assertThat(record.value()).isEqualTo("not-json");
            assertThat(record.key()).isEqualTo("synthetic-key");
            assertThat(new String(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                    StandardCharsets.UTF_8)).isEqualTo(scenario.topic);
            assertThat(ByteBuffer.wrap(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value())
                    .getLong()).isZero();
            assertThat(scenario.count("event_consumer_failure_total", "invalid")).isEqualTo(1);
            assertThat(scenario.count("event_consumer_recovered_total", "invalid")).isEqualTo(1);
        }
    }

    @Test
    void invalidInventoryIdentityRollsBackInboxBeforeQuarantine() throws Exception {
        try (var scenario = new Scenario(true)) {
            UUID eventId = UUID.randomUUID();
            String malformed = envelope(eventId).replace("SalesOrderCreated", "InventoryReceived")
                    .replace("\"aggregateId\":\"" + eventId + "\"", "\"aggregateId\":\"invalid\"");
            scenario.send(malformed);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(scenario.committedOffset()).isEqualTo(1));
            assertThat(inboxCount(eventId)).isZero();
            assertThat(scenario.dlqRecords(Duration.ofSeconds(2)))
                    .extracting(ConsumerRecord::value).containsExactly(malformed);
            assertThat(scenario.count("event_consumer_failure_total", "invalid")).isEqualTo(1);
            assertThat(scenario.count("event_consumer_recovered_total", "invalid")).isEqualTo(1);
        }
    }

    @Test
    void unavailableDlqRetainsOffsetAcrossConsumerRestartThenRecovers() throws Exception {
        try (var scenario = new Scenario(false)) {
            UUID eventId = UUID.randomUUID();
            scenario.send("not-json");
            scenario.send(envelope(eventId));
            await().atMost(Duration.ofSeconds(25)).until(() -> scenario.recoveryFailures() > 0);
            scenario.stop();
            assertThat(scenario.committedOffset()).isLessThanOrEqualTo(0);
            assertThat(inboxCount(eventId)).isZero();
            assertThat(scenario.count("event_consumer_recovered_total", "invalid")).isZero();
            scenario.createDlq();
            scenario.start(); // New real consumer, same broker-persisted group offset; not a JVM kill.
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
                assertThat(scenario.committedOffset()).isEqualTo(2);
                assertThat(inboxCount(eventId)).isEqualTo(1);
            });
            assertThat(scenario.dlqRecords(Duration.ofSeconds(2)))
                    .extracting(ConsumerRecord::value).containsExactly("not-json");
        }
    }

    @Test
    void crashWindowGatesRunAfterTheSideEffectIsDurableAndBeforeTheOffsetCommit() throws Exception {
        record Observation(long recordOffset, long committedOffset, int durableEffects) {
        }
        try (var scenario = new Scenario(true, false)) {
            UUID eventId = UUID.randomUUID();
            Map<ConsumerCrashWindowGate.Window, Observation> observed = new ConcurrentHashMap<>();
            scenario.crashWindowGate = (window, record) -> {
                try {
                    int effects = window == ConsumerCrashWindowGate.Window.AFTER_INBOX_COMMIT
                            ? inboxCount(eventId) : scenario.dlqRecords(Duration.ofSeconds(1)).size();
                    observed.put(window, new Observation(record.offset(), scenario.committedOffset(), effects));
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            };
            scenario.send(envelope(eventId));
            scenario.send("not-json");
            scenario.start();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(scenario.committedOffset()).isEqualTo(2));
            // Each gate saw its own side effect committed/acknowledged and its own offset still uncommitted.
            assertThat(observed).containsExactlyInAnyOrderEntriesOf(Map.of(
                    ConsumerCrashWindowGate.Window.AFTER_INBOX_COMMIT, new Observation(0, -1, 1),
                    ConsumerCrashWindowGate.Window.AFTER_DEAD_LETTER_SEND, new Observation(1, 1, 1)));
        }
    }

    private int inboxCount(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM consumer_inbox WHERE event_id = ?", Integer.class, eventId);
    }

    private String envelope(UUID eventId) {
        return """
                {"eventId":"%s","eventType":"SalesOrderCreated","aggregateId":"%s",
                 "organizationId":"11111111-1111-1111-1111-111111111111","aggregateVersion":1,
                 "schemaVersion":1,"occurredAt":"2026-09-07T00:00:00Z","traceId":"synthetic",
                 "correlationId":"%s","payload":{}}
                """.formatted(eventId, eventId, eventId);
    }

    private final class Scenario implements AutoCloseable {
        private final String topic = "consumer-recovery-" + UUID.randomUUID();
        private final String group = "verify-" + UUID.randomUUID();
        private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        private final AtomicReference<RuntimeException> lastFailure = new AtomicReference<>();
        private ConsumerCrashWindowGate crashWindowGate = ConsumerCrashWindowGate.NONE;
        private final Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()));
        private final DefaultKafkaProducerFactory<Object, Object> producerFactory = new DefaultKafkaProducerFactory<>(
                Map.of("bootstrap.servers", KAFKA.getBootstrapServers(), "acks", "all",
                        "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class,
                        "max.block.ms", 1000, "delivery.timeout.ms", 1500, "request.timeout.ms", 1000));
        private final KafkaTemplate<Object, Object> kafka = new KafkaTemplate<>(producerFactory);
        private ConcurrentMessageListenerContainer<Object, Object> container;

        private Scenario(boolean createDlq) throws Exception {
            this(createDlq, true);
        }

        private Scenario(boolean createDlq, boolean startImmediately) throws Exception {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            if (createDlq) createDlq();
            if (startImmediately) start();
        }

        private void createDlq() throws Exception {
            admin.createTopics(List.of(new NewTopic(topic + ".DLQ", 1, (short) 1)))
                    .all().get(10, TimeUnit.SECONDS);
        }

        @SuppressWarnings("unchecked")
        private void start() {
            var configuration = new KafkaConsumerConfiguration();
            var consumerConfig = new HashMap<>(configuredConsumerFactory.getConfigurationProperties());
            consumerConfig.put("bootstrap.servers", KAFKA.getBootstrapServers());
            consumerConfig.put("group.id", group);
            var consumerFactory = new DefaultKafkaConsumerFactory<Object, Object>(consumerConfig);
            var factory = configuration.kafkaListenerContainerFactory(consumerFactory,
                    configuration.domainEventErrorHandler(kafka, meters, crashWindowGate));
            container = factory.createContainer(topic);
            container.getContainerProperties().setPollTimeout(100);
            var listener = new DomainEventListener(inbox, Tracer.NOOP, Propagator.NOOP, crashWindowGate);
            container.setupMessageListener((MessageListener<Object, Object>) record -> {
                try {
                    listener.onEvent((ConsumerRecord<String, String>) (ConsumerRecord<?, ?>) record);
                } catch (RuntimeException failure) {
                    lastFailure.set(failure);
                    throw failure;
                }
            });
            container.start();
        }

        private void send(String value) throws Exception {
            kafka.send(new ProducerRecord<>(topic, 0, "synthetic-key", value)).get(10, TimeUnit.SECONDS);
        }

        private double count(String name, String reason) {
            var counter = meters.find(name).tag("reason", reason).counter();
            return counter == null ? 0 : counter.count();
        }

        private double recoveryFailures() {
            var counter = meters.find("event_consumer_recovery_failed_total").counter();
            return counter == null ? 0 : counter.count();
        }

        private long committedOffset() throws Exception {
            var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS);
            var offset = offsets.get(new TopicPartition(topic, 0));
            return offset == null ? -1 : offset.offset();
        }

        private List<ConsumerRecord<String, String>> dlqRecords(Duration duration) {
            try (var consumer = new KafkaConsumer<String, String>(Map.of(
                    "bootstrap.servers", KAFKA.getBootstrapServers(), "group.id", "audit-" + UUID.randomUUID(),
                    "enable.auto.commit", false, "key.deserializer", StringDeserializer.class,
                    "value.deserializer", StringDeserializer.class))) {
                var partition = new TopicPartition(topic + ".DLQ", 0);
                consumer.assign(List.of(partition));
                consumer.seekToBeginning(List.of(partition));
                List<ConsumerRecord<String, String>> records = new ArrayList<>();
                long deadline = System.nanoTime() + duration.toNanos();
                while (System.nanoTime() < deadline) consumer.poll(Duration.ofMillis(100)).forEach(records::add);
                return records;
            }
        }

        private void stop() {
            if (container != null) container.stop();
        }

        @Override
        public void close() {
            stop();
            kafka.destroy();
            producerFactory.destroy();
            admin.close(Duration.ofSeconds(5));
            meters.close();
        }
    }
}
