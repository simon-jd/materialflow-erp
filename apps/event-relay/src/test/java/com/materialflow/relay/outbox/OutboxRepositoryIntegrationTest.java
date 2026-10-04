package com.materialflow.relay.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.kafka.core.KafkaTemplate;

class OutboxRepositoryIntegrationTest extends OutboxPostgresFixture {
    @Test
    void expiredFinalAttemptBecomesDeadLetterInsteadOfRemainingClaimedForever() {
        UUID id = insertEvent("test");
        repository.claim("relay-a", 1, 1, LEASE);
        expire(id);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var properties = new RelayProperties("recovery", 1, 1, LEASE,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(4));
        var meters = new SimpleMeterRegistry();
        new OutboxRelay(repository, kafka, properties, meters, Tracer.NOOP, Propagator.NOOP).publishBatch();
        assertThat(status(id)).isEqualTo("DEAD_LETTER");
        assertThat(repository.backlog().count()).isZero();
        assertThat(meters.get("outbox_exhausted_total").counter().count()).isEqualTo(1);
        assertThat(meters.get("outbox_dead_letter_count").gauge().value()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT claimed_by, claimed_at, claim_token, last_error FROM outbox_events"))
                .containsEntry("claimed_by", null).containsEntry("claimed_at", null).containsEntry("claim_token", null)
                .extractingByKey("last_error").asString().contains("delivery may have occurred");
    }

    @Test
    void oldCompletionCannotOverwriteAReclaimByTheSameInstanceName() {
        UUID id = insertEvent("test");
        OutboxEvent old = repository.claim("reused-hostname", 1, 5, LEASE).getFirst();
        expire(id);
        OutboxEvent current = repository.claim("reused-hostname", 1, 5, LEASE).getFirst();
        assertThat(current.claimToken()).isNotEqualTo(old.claimToken());
        assertThat(repository.markPublished(old.id(), "reused-hostname", old.claimToken())).isFalse();
        assertThat(repository.markFailed(old.id(), "reused-hostname", old.claimToken(),
                old.attemptCount(), 5, Instant.now(), "late failure")).isFalse();
        assertThat(status(id)).isEqualTo("CLAIMED");
        assertThat(repository.markPublished(id, "wrong-owner", current.claimToken())).isFalse();
        assertThat(repository.markPublished(id, "reused-hostname", current.claimToken())).isTrue();
    }

    @Test
    void manualAttemptResetDoesNotReviveAnOldClaimToken() {
        UUID id = insertEvent("test");
        OutboxEvent old = repository.claim("relay", 1, 5, LEASE).getFirst();
        jdbc.update("UPDATE outbox_events SET status = 'FAILED', attempt_count = 0 WHERE id = ?", id);
        OutboxEvent current = repository.claim("relay", 1, 5, LEASE).getFirst();
        assertThat(current.attemptCount()).isEqualTo(old.attemptCount());
        assertThat(repository.markPublished(id, "relay", old.claimToken())).isFalse();
        assertThat(repository.markPublished(id, "relay", current.claimToken())).isTrue();
    }

    @Test
    void concurrentRelayInstancesClaimDisjointBatchesAndRecoverStaleClaims() throws Exception {
        for (int index = 0; index < 12; index++) insertEvent("test");
        CountDownLatch start = new CountDownLatch(1);
        List<OutboxEvent> first;
        List<OutboxEvent> second;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> { start.await(); return repository.claim("relay-a", 10, 5, LEASE); });
            var b = executor.submit(() -> { start.await(); return repository.claim("relay-b", 10, 5, LEASE); });
            start.countDown();
            first = a.get(10, TimeUnit.SECONDS);
            second = b.get(10, TimeUnit.SECONDS);
        }
        var all = java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
        assertThat(all).hasSize(12).allMatch(event -> event.attemptCount() == 1);
        assertThat(new HashSet<>(all.stream().map(OutboxEvent::id).toList())).hasSize(12);
        assertThat(first).isNotEmpty();
        assertThat(second).isNotEmpty();
        OutboxEvent old = first.getFirst();
        expire(old.id());
        OutboxEvent recovered = repository.claim("relay-recovery", 1, 5, LEASE).getFirst();
        assertThat(recovered.id()).isEqualTo(old.id());
        assertThat(recovered.attemptCount()).isEqualTo(2);
        assertThat(repository.markPublished(old.id(), "relay-a", old.claimToken())).isFalse();
    }

    @Test
    void finalLiveClaimAndFutureRetryAreNotSweptAndRecoveryIsBounded() {
        UUID live = insertEvent("test");
        repository.claim("relay", 1, 1, LEASE);
        UUID delayed = insertEvent("test");
        jdbc.update("UPDATE outbox_events SET status = 'FAILED', attempt_count = 1, "
                + "next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE id = ?", delayed);
        assertThat(repository.deadLetterExhausted(1, LEASE, 1)).isZero();
        expire(live);
        UUID another = insertEvent("test");
        jdbc.update("UPDATE outbox_events SET attempt_count = 1 WHERE id = ?", another);
        assertThat(repository.deadLetterExhausted(1, LEASE, 1)).isEqualTo(1);
        assertThat(repository.deadLetterCount()).isEqualTo(1);
        assertThat(repository.deadLetterExhausted(1, LEASE, 1)).isEqualTo(1);
        assertThat(repository.deadLetterExhausted(1, LEASE, 1)).isZero();
        assertThat(status(delayed)).isEqualTo("FAILED");
    }

    @Test
    void failedPublishKeepsRetryScheduleAndTruncatesErrorThenDeadLetters() {
        UUID id = insertEvent("test");
        OutboxEvent first = repository.claim("relay", 1, 2, LEASE).getFirst();
        assertThat(repository.markFailed(id, "relay", first.claimToken(), 1, 2,
                Instant.now().plusSeconds(3600), "x".repeat(2000))).isTrue();
        assertThat(repository.claim("relay", 1, 2, LEASE)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT length(last_error) FROM outbox_events", Integer.class)).isEqualTo(1000);
        jdbc.update("UPDATE outbox_events SET next_attempt_at = CURRENT_TIMESTAMP");
        OutboxEvent last = repository.claim("relay", 1, 2, LEASE).getFirst();
        assertThat(repository.markFailed(id, "relay", last.claimToken(), 2, 2, Instant.now(), "offline")).isTrue();
        assertThat(status(id)).isEqualTo("DEAD_LETTER");
        assertThat(repository.markPublished(id, "relay", last.claimToken())).isFalse();
    }

    @Test
    void claimAndRecoverySkipRowsLockedByAnotherDatabaseTransaction() throws Exception {
        UUID id = insertEvent("test");
        repository.claim("relay", 1, 1, LEASE);
        expire(id);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM outbox_events WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, id);
                statement.executeQuery().close();
            }
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                assertThat(executor.submit(() -> repository.deadLetterExhausted(1, LEASE, 10))
                        .get(5, TimeUnit.SECONDS)).isZero();
                assertThat(executor.submit(() -> repository.claim("other", 1, 5, LEASE))
                        .get(5, TimeUnit.SECONDS)).isEmpty();
            }
            connection.rollback();
        }
        assertThat(repository.deadLetterExhausted(1, LEASE, 10)).isEqualTo(1);
    }

    @Test
    void upgradesV5WithAnExistingClaimWithoutChangingItsPayload() throws Exception {
        Flyway.configure().dataSource(dataSource).schemas("upgrade_check").defaultSchema("upgrade_check")
                .target("5").load().migrate();
        try (var connection = dataSource.getConnection()) {
            var legacy = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            legacy.execute("SET search_path TO upgrade_check");
            legacy.execute("""
                    INSERT INTO outbox_events (id, organization_id, topic, event_type, aggregate_id,
                        aggregate_version, schema_version, event_key, payload, trace_id, correlation_id,
                        occurred_at, status, claimed_by, claimed_at, attempt_count)
                    SELECT gen_random_uuid(), id, 'test', 'OrderCreated', gen_random_uuid(), 1, 1,
                        'key', '{"preserved":true}', 'test', gen_random_uuid(), CURRENT_TIMESTAMP,
                        'CLAIMED', 'old-binary', CURRENT_TIMESTAMP - INTERVAL '2 minutes', 1
                    FROM organizations LIMIT 1
                    """);
            Flyway.configure().dataSource(dataSource).schemas("upgrade_check").defaultSchema("upgrade_check")
                    .load().migrate();
            var upgraded = new OutboxRepository(legacy);
            OutboxEvent event = upgraded.claim("new-binary", 1, 5, LEASE).getFirst();
            assertThat(event.claimToken()).isNotNull();
            assertThat(event.payload()).contains("\"preserved\": true");
            assertThat(upgraded.markPublished(event.id(), "new-binary", event.claimToken())).isTrue();
        }
    }
}
