package com.materialflow.relay.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("materialflow.relay")
public record RelayProperties(
        String instanceId,
        int batchSize,
        int maxAttempts,
        Duration claimTimeout,
        Duration publishTimeout,
        Duration initialBackoff,
        Duration maxBackoff) {
    public RelayProperties {
        if (instanceId == null || instanceId.isBlank() || batchSize <= 0 || maxAttempts <= 0) {
            throw new IllegalArgumentException("Relay requires an instance ID and positive batch/attempt limits");
        }
        if (!positiveMillis(claimTimeout) || !positiveMillis(publishTimeout)
                || !positiveMillis(initialBackoff) || !positiveMillis(maxBackoff)
                || claimTimeout.compareTo(publishTimeout) <= 0
                || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("Relay durations must be positive; claim timeout must exceed "
                    + "publish timeout and max backoff must be at least initial backoff");
        }
    }

    private static boolean positiveMillis(Duration value) {
        return value != null && value.compareTo(Duration.ofMillis(1)) >= 0;
    }
}
