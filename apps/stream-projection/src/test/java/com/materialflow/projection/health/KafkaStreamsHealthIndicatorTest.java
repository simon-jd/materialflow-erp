package com.materialflow.projection.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.streams.KafkaStreams;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class KafkaStreamsHealthIndicatorTest {
    @Test
    void reportsRunningStreamsAsUp() {
        assertThat(KafkaStreamsHealthIndicator.healthFor(KafkaStreams.State.RUNNING).getStatus())
                .isEqualTo(Status.UP);
    }

    @Test
    void reportsFailedStreamsAsDown() {
        assertThat(KafkaStreamsHealthIndicator.healthFor(KafkaStreams.State.ERROR).getStatus())
                .isEqualTo(Status.DOWN);
    }
}
