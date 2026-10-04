package com.materialflow.relay.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(
        UUID id,
        String topic,
        String eventKey,
        String payload,
        String traceParent,
        int attemptCount,
        Instant occurredAt,
        UUID claimToken) {
}
