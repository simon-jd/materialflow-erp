package com.materialflow.erp.audit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.materialflow.contracts.events.EventEnvelope;
import com.materialflow.erp.common.TraceIdFilter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AuditOutboxWriter {
    public static final String DOMAIN_TOPIC = "materialflow.domain-events.v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    public AuditOutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, Tracer tracer, Propagator propagator) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public UUID write(
            UUID organizationId,
            UUID actorId,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String eventType,
            Map<String, ?> beforeState,
            Map<String, ?> payload,
            String reason,
            UUID causationId,
            UUID correlationId) {
        TracingContext tracing = currentTracingContext();
        String traceId = tracing.traceId();
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        var envelope = new EventEnvelope<>(eventId, eventType, aggregateId, aggregateVersion,
                organizationId, now, 1, traceId, causationId, correlationId, payload);

        jdbc.update("""
                INSERT INTO audit_log
                    (id, organization_id, actor_id, action, aggregate_type, aggregate_id,
                     before_state, after_state, reason, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
                """, UUID.randomUUID(), organizationId, actorId, eventType, aggregateType, aggregateId,
                json(beforeState), json(payload), reason, traceId);
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, organization_id, topic, event_type, aggregate_id, aggregate_version,
                     schema_version, event_key, payload, trace_id, trace_parent,
                     causation_id, correlation_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
                """, eventId, organizationId, DOMAIN_TOPIC, eventType, aggregateId, aggregateVersion,
                1, aggregateId.toString(), json(envelope), traceId, tracing.traceParent(),
                causationId, correlationId, Timestamp.from(now));
        return eventId;
    }

    private TracingContext currentTracingContext() {
        Span current = tracer.currentSpan();
        if (current == null || current.isNoop()) {
            return new TracingContext(TraceIdFilter.currentTraceId(), null);
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(current.context(), carrier, Map::put);
        String traceParent = carrier.entrySet().stream()
                .filter(entry -> "traceparent".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseGet(() -> w3cTraceParent(current));
        return new TracingContext(current.context().traceId(), traceParent);
    }

    private String w3cTraceParent(Span current) {
        String flags = Boolean.TRUE.equals(current.context().sampled()) ? "01" : "00";
        return "00-" + current.context().traceId() + "-" + current.context().spanId() + "-" + flags;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize audit/outbox payload", exception);
        }
    }

    private record TracingContext(String traceId, String traceParent) {
    }
}
