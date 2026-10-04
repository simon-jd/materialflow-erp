package com.materialflow.audit;

import com.materialflow.contracts.events.EventEnvelope;
import java.time.Instant;

public record OutboxRecord(
        EventEnvelope<?> event,
        String topic,
        Status status,
        int attemptCount,
        Instant nextAttemptAt,
        String lastError) {

    public enum Status {
        PENDING,
        CLAIMED,
        PUBLISHED,
        FAILED,
        DEAD_LETTER
    }
}

