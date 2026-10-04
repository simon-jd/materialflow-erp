package com.materialflow.contracts.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        UUID aggregateId,
        long aggregateVersion,
        UUID organizationId,
        Instant occurredAt,
        int schemaVersion,
        String traceId,
        UUID causationId,
        UUID correlationId,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        requireText(eventType, "eventType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        if (aggregateVersion < 1) {
            throw new IllegalArgumentException("aggregateVersion must be positive");
        }
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        requireText(traceId, "traceId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(payload, "payload");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

