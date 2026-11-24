package com.materialflow.erp.events;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How quickly the domain-event consumer group notices a dead member and hands its
 * partitions to a replacement. Trade-offs are recorded in ADR 0011.
 *
 * @param sessionTimeout time without heartbeats before the coordinator evicts a member
 * @param heartbeatInterval heartbeat period; must be shorter than the session timeout
 * @param groupInstanceId optional static-membership identity; blank means dynamic membership
 */
@ConfigurationProperties("materialflow.kafka.consumer.failure-detection")
public record ConsumerFailureDetectionProperties(
        Duration sessionTimeout,
        Duration heartbeatInterval,
        String groupInstanceId) {
    public ConsumerFailureDetectionProperties {
        if (sessionTimeout == null || heartbeatInterval == null || heartbeatInterval.isNegative()
                || heartbeatInterval.isZero() || heartbeatInterval.compareTo(sessionTimeout) >= 0) {
            throw new IllegalArgumentException("Consumer failure detection requires a positive heartbeat interval "
                    + "shorter than the session timeout");
        }
    }

    Map<String, Object> consumerConfigs() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, Math.toIntExact(sessionTimeout.toMillis()));
        configs.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, Math.toIntExact(heartbeatInterval.toMillis()));
        if (groupInstanceId != null && !groupInstanceId.isBlank()) {
            configs.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, groupInstanceId.strip());
        }
        return configs;
    }
}
