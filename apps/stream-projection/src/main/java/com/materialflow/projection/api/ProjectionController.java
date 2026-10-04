package com.materialflow.projection.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.materialflow.projection.config.ProjectionTopology;
import java.util.UUID;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projections/aggregates")
public class ProjectionController {
    private final StreamsBuilderFactoryBean streamsFactory;
    private final ObjectMapper objectMapper;

    public ProjectionController(StreamsBuilderFactoryBean streamsFactory, ObjectMapper objectMapper) {
        this.streamsFactory = streamsFactory;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{aggregateId}")
    public ResponseEntity<?> get(@PathVariable UUID aggregateId) {
        var streams = streamsFactory.getKafkaStreams();
        if (streams == null) {
            return ResponseEntity.status(503).body(new ProjectionUnavailable("PROJECTION_STARTING"));
        }
        try {
            var store = streams.store(StoreQueryParameters.fromNameAndType(
                    ProjectionTopology.LATEST_AGGREGATE_STORE,
                    QueryableStoreTypes.<String, String>keyValueStore()));
            String value = store.get(aggregateId.toString());
            if (value == null) return ResponseEntity.notFound().build();
            JsonNode json = objectMapper.readTree(value);
            return ResponseEntity.ok(json);
        } catch (InvalidStateStoreException exception) {
            return ResponseEntity.status(503).body(new ProjectionUnavailable("PROJECTION_REBALANCING"));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(new ProjectionUnavailable("PROJECTION_READ_FAILED"));
        }
    }

    private record ProjectionUnavailable(String errorCode) {
    }
}
