package com.materialflow.relay.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelay {
    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final RelayProperties properties;
    private final Tracer tracer;
    private final Propagator propagator;
    private final Counter published;
    private final Counter failed;
    private final Counter deadLettered;
    private final Counter claimLost;
    private final Counter exhausted;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong oldestAgeMillis = new AtomicLong();
    private final AtomicLong deadLetters = new AtomicLong();

    public OutboxRelay(
            OutboxRepository repository,
            KafkaTemplate<String, String> kafka,
            RelayProperties properties,
            MeterRegistry registry,
            Tracer tracer,
            Propagator propagator) {
        this.repository = repository;
        this.kafka = kafka;
        this.properties = properties;
        this.tracer = tracer;
        this.propagator = propagator;
        this.published = registry.counter("outbox_published_total");
        this.failed = registry.counter("outbox_publish_failure_total");
        this.deadLettered = registry.counter("outbox_dead_letter_total");
        this.claimLost = registry.counter("outbox_claim_lost_total");
        this.exhausted = registry.counter("outbox_exhausted_total");
        Gauge.builder("outbox_pending_count", backlog, AtomicLong::get).register(registry);
        Gauge.builder("outbox_dead_letter_count", deadLetters, AtomicLong::get).register(registry);
        Gauge.builder("outbox_oldest_event_age_seconds", oldestAgeMillis,
                value -> value.get() / 1000.0).register(registry);
    }

    @Scheduled(fixedDelayString = "${materialflow.relay.poll-delay:500ms}")
    public void publishBatch() {
        int terminalized = repository.deadLetterExhausted(properties.maxAttempts(),
                properties.claimTimeout(), properties.batchSize());
        exhausted.increment(terminalized);
        deadLettered.increment(terminalized);
        for (int index = 0; index < properties.batchSize() && !Thread.currentThread().isInterrupted(); index++) {
            // Claim only when ready to send: queued records must not spend their lease waiting for other ACKs.
            var claimed = repository.claim(properties.instanceId(), 1,
                    properties.maxAttempts(), properties.claimTimeout());
            if (claimed.isEmpty()) break;
            publishOutsideDatabaseTransaction(claimed.getFirst());
        }
        var currentBacklog = repository.backlog();
        backlog.set(currentBacklog.count());
        oldestAgeMillis.set(Math.round(currentBacklog.oldestAgeSeconds() * 1000));
        deadLetters.set(repository.deadLetterCount());
    }

    private void publishOutsideDatabaseTransaction(OutboxEvent event) {
        Span span = relaySpan(event);
        try {
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                ProducerRecord<String, String> record = kafkaRecord(event, span);
                kafka.send(record).get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                span.error(exception);
                Thread.currentThread().interrupt();
                return; // Outcome is uncertain. Leave the claim for lease recovery, stop taking more work.
            } catch (Exception exception) {
                span.error(exception);
                Duration delay = retryDelay(event.attemptCount());
                boolean updated = repository.markFailed(event.id(), properties.instanceId(), event.claimToken(),
                        event.attemptCount(), properties.maxAttempts(), Instant.now().plus(delay),
                        rootMessage(exception));
                failed.increment();
                if (!updated) claimLost.increment();
                else if (event.attemptCount() >= properties.maxAttempts()) deadLettered.increment();
                return;
            }
            // A database failure after ACK must stay an uncertain claim, not become a Kafka failure.
            if (repository.markPublished(event.id(), properties.instanceId(), event.claimToken())) published.increment();
            else claimLost.increment();
        } catch (RuntimeException exception) {
            span.error(exception);
            throw exception;
        } finally {
            span.end();
        }
    }

    private Span relaySpan(OutboxEvent event) {
        Span.Builder builder;
        if (event.traceParent() == null || event.traceParent().isBlank()) {
            builder = tracer.spanBuilder();
        } else {
            builder = propagator.extract(event.traceParent(),
                    (carrier, key) -> "traceparent".equalsIgnoreCase(key) ? carrier : null);
        }
        return builder.name("outbox publish")
                .tag("messaging.destination.name", event.topic())
                .tag("messaging.message.id", event.id().toString())
                .start();
    }

    private ProducerRecord<String, String> kafkaRecord(OutboxEvent event, Span span) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                event.topic(), event.eventKey(), event.payload());
        propagator.inject(span.context(), record.headers(), (headers, key, value) -> {
            headers.remove(key);
            headers.add(key, value.getBytes(StandardCharsets.UTF_8));
        });
        return record;
    }

    private Duration retryDelay(int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 20);
        long candidate;
        try {
            candidate = Math.multiplyExact(properties.initialBackoff().toMillis(), multiplier);
        } catch (ArithmeticException exception) {
            candidate = properties.maxBackoff().toMillis();
        }
        return Duration.ofMillis(Math.min(candidate, properties.maxBackoff().toMillis()));
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + String.valueOf(current.getMessage());
    }
}
