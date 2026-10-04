package com.materialflow.projection.health;

import org.apache.kafka.streams.KafkaStreams;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

@Component("kafkaStreams")
public class KafkaStreamsHealthIndicator implements HealthIndicator {
    private final StreamsBuilderFactoryBean streamsFactory;

    public KafkaStreamsHealthIndicator(StreamsBuilderFactoryBean streamsFactory) {
        this.streamsFactory = streamsFactory;
    }

    @Override
    public Health health() {
        KafkaStreams streams = streamsFactory.getKafkaStreams();
        if (streams == null) {
            return Health.outOfService().withDetail("state", "NOT_CREATED").build();
        }
        return healthFor(streams.state());
    }

    static Health healthFor(KafkaStreams.State state) {
        if (state == KafkaStreams.State.RUNNING || state == KafkaStreams.State.REBALANCING) {
            return Health.up().withDetail("state", state.name()).build();
        }
        return Health.down().withDetail("state", state.name()).build();
    }
}
