package com.materialflow.relay.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;

class OutboxRelayTest {
    private final OutboxRepository repository = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RelayProperties properties = new RelayProperties("relay-test", 3, 3, Duration.ofSeconds(30),
            Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(4));

    @BeforeEach
    void defaults() {
        when(repository.backlog()).thenReturn(new OutboxRepository.Backlog(0, 0));
        when(repository.markPublished(any(), any(), any())).thenReturn(true);
        when(repository.markFailed(any(), any(), any(), anyInt(), anyInt(), any(), any())).thenReturn(true);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void claimsTheNextEventOnlyAfterThePreviousAcknowledgmentIsRecorded() {
        OutboxEvent a = event(1);
        OutboxEvent b = event(1);
        when(repository.claim("relay-test", 1, 3, Duration.ofSeconds(30)))
                .thenReturn(List.of(a), List.of(b), List.of());
        relay().publishBatch();
        var order = inOrder(repository, kafka);
        order.verify(repository).claim("relay-test", 1, 3, Duration.ofSeconds(30));
        order.verify(kafka).send(any(ProducerRecord.class));
        order.verify(repository).markPublished(a.id(), "relay-test", a.claimToken());
        order.verify(repository).claim("relay-test", 1, 3, Duration.ofSeconds(30));
        order.verify(kafka).send(any(ProducerRecord.class));
        order.verify(repository).markPublished(b.id(), "relay-test", b.claimToken());
        assertThat(meters.get("outbox_published_total").counter().count()).isEqualTo(2);
    }

    @Test
    void recordsBoundedBackoffAndDeadLetterAttemptWhenKafkaFails() {
        OutboxEvent event = claimOne(3);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("synthetic Kafka outage")));
        Instant before = Instant.now().plusSeconds(3);
        relay().publishBatch();
        ArgumentCaptor<Instant> retryAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).markFailed(eq(event.id()), eq("relay-test"), eq(event.claimToken()), eq(3), eq(3),
                retryAt.capture(), eq("IllegalStateException: synthetic Kafka outage"));
        assertThat(retryAt.getValue()).isBetween(before, Instant.now().plusSeconds(5));
        assertThat(meters.get("outbox_dead_letter_total").counter().count()).isEqualTo(1);
    }

    @Test
    void staleAcknowledgmentIsCountedWithoutAttemptingAFailureWrite() {
        OutboxEvent event = claimOne(1);
        when(repository.markPublished(event.id(), "relay-test", event.claimToken())).thenReturn(false);
        relay().publishBatch();
        assertThat(meters.get("outbox_claim_lost_total").counter().count()).isEqualTo(1);
        assertThat(meters.get("outbox_published_total").counter().count()).isZero();
        verify(repository, never()).markFailed(any(), any(), any(), anyInt(), anyInt(), any(), any());
        verify(repository).backlog();
    }

    @Test
    void staleFailureDoesNotCountAsADeadLetterTransition() {
        OutboxEvent event = claimOne(3);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("late failure")));
        when(repository.markFailed(eq(event.id()), any(), any(), anyInt(), anyInt(), any(), any())).thenReturn(false);
        relay().publishBatch();
        assertThat(meters.get("outbox_claim_lost_total").counter().count()).isEqualTo(1);
        assertThat(meters.get("outbox_dead_letter_total").counter().count()).isZero();
    }

    @Test
    void databaseFailureAfterBrokerAckLeavesTheClaimForRecovery() {
        claimOne(1);
        when(repository.markPublished(any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("database disconnected after ACK"));
        assertThatThrownBy(() -> relay().publishBatch()).isInstanceOf(DataAccessResourceFailureException.class);
        verify(repository, never()).markFailed(any(), any(), any(), anyInt(), anyInt(), any(), any());
        assertThat(meters.get("outbox_publish_failure_total").counter().count()).isZero();
    }

    @Test
    void interruptPreservesTheClaimAndStopsClaimingMoreWork() {
        OutboxEvent event = claimOne(1);
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(call -> {
            Thread.currentThread().interrupt();
            return new CompletableFuture<>();
        });
        try {
            relay().publishBatch();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(repository).claim("relay-test", 1, 3, Duration.ofSeconds(30));
            verify(repository, never()).markPublished(eq(event.id()), any(), any());
            verify(repository, never()).markFailed(any(), any(), any(), anyInt(), anyInt(), any(), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void timeoutRecordsAnUncertainDeliveryForBoundedRetry() {
        claimOne(1);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
        relay().publishBatch();
        assertThat(meters.get("outbox_publish_failure_total").counter().count()).isEqualTo(1);
        verify(repository, never()).markPublished(any(), any(), any());
    }

    @Test
    void rejectsInvalidLeaseAndRetryConfiguration() {
        assertThatThrownBy(() -> new RelayProperties("relay", 1, 3, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(4)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RelayProperties("relay", 0, 3, Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(4)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private OutboxRelay relay() {
        return new OutboxRelay(repository, kafka, properties, meters, Tracer.NOOP, Propagator.NOOP);
    }

    private OutboxEvent claimOne(int attempt) {
        OutboxEvent event = event(attempt);
        when(repository.claim("relay-test", 1, 3, Duration.ofSeconds(30))).thenReturn(List.of(event), List.of());
        return event;
    }

    private OutboxEvent event(int attempt) {
        return new OutboxEvent(UUID.randomUUID(), "materialflow.domain-events.v1", "order-1", "{}",
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01", attempt, Instant.now(), UUID.randomUUID());
    }
}
