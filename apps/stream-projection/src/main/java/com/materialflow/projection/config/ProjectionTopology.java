package com.materialflow.projection.config;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.state.KeyValueStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProjectionTopology {
    public static final String LATEST_AGGREGATE_STORE = "aggregate-latest-v1";
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public ProjectionTopology(ObjectMapper objectMapper, MeterRegistry meters) {
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    @Bean
    public KStream<String, String> materialFlowTopology(
            StreamsBuilder builder,
            @Value("${materialflow.topics.domain:materialflow.domain-events.v1}") String domainTopic,
            @Value("${materialflow.topics.dlq:materialflow.projection-dlq.v1}") String dlqTopic) {
        KStream<String, String> source = builder.stream(domainTopic,
                Consumed.with(Serdes.String(), Serdes.String()));
        KStream<String, String> valid = source.filter(this::validEnvelope);
        source.filterNot(this::validEnvelope)
                .peek((key, value) -> meters.counter("projection_invalid_event_total").increment())
                .to(dlqTopic);

        valid.groupByKey(Grouped.with(Serdes.String(), Serdes.String()))
                .reduce(this::newestAggregateVersion,
                        Materialized.<String, String, KeyValueStore<Bytes, byte[]>>as(LATEST_AGGREGATE_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(Serdes.String()));
        valid.peek((key, value) -> meters.counter("projection_event_total", "event_type", eventType(value))
                .increment());
        return valid;
    }

    private boolean validEnvelope(String key, String value) {
        if (key == null || value == null) return false;
        try {
            JsonNode root = objectMapper.readTree(value);
            return root.hasNonNull("eventId") && root.hasNonNull("eventType")
                    && root.hasNonNull("aggregateId") && root.path("aggregateVersion").asLong() > 0
                    && key.equals(root.path("aggregateId").asText());
        } catch (Exception exception) {
            return false;
        }
    }

    private String newestAggregateVersion(String current, String candidate) {
        try {
            long currentVersion = objectMapper.readTree(current).path("aggregateVersion").asLong();
            long candidateVersion = objectMapper.readTree(candidate).path("aggregateVersion").asLong();
            return candidateVersion >= currentVersion ? candidate : current;
        } catch (Exception exception) {
            return current;
        }
    }

    private String eventType(String value) {
        try {
            return objectMapper.readTree(value).path("eventType").asText("unknown");
        } catch (Exception exception) {
            return "unknown";
        }
    }
}
