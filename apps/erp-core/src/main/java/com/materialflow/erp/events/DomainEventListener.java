package com.materialflow.erp.events;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "materialflow.kafka.consumer.enabled", havingValue = "true")
public class DomainEventListener {
    private final DomainEventInboxApplicationService inbox;
    private final Tracer tracer;
    private final Propagator propagator;
    private final ConsumerCrashWindowGate crashWindowGate;

    public DomainEventListener(
            DomainEventInboxApplicationService inbox,
            Tracer tracer,
            Propagator propagator,
            ConsumerCrashWindowGate crashWindowGate) {
        this.inbox = inbox;
        this.tracer = tracer;
        this.propagator = propagator;
        this.crashWindowGate = crashWindowGate;
    }

    @KafkaListener(topics = "${materialflow.kafka.consumer.topic:materialflow.domain-events.v1}",
            groupId = "${materialflow.kafka.consumer.group-id:erp-core-reallocation-v1}")
    public void onEvent(ConsumerRecord<String, String> record) {
        Span span = propagator.extract(record.headers(), (headers, key) -> {
                    var header = headers.lastHeader(key);
                    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
                })
                .name("domain event consume")
                .kind(Span.Kind.CONSUMER)
                .tag("messaging.destination.name", record.topic())
                .tag("messaging.kafka.offset", record.offset())
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            inbox.consume(record.value());
            // consume() has committed; the RECORD-mode offset commit runs after this method returns.
            crashWindowGate.enter(ConsumerCrashWindowGate.Window.AFTER_INBOX_COMMIT, record);
        } catch (RuntimeException exception) {
            span.error(exception);
            throw exception;
        } finally {
            span.end();
        }
    }
}
