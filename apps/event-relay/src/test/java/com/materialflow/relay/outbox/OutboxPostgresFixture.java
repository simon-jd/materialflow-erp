package com.materialflow.relay.outbox;

import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
abstract class OutboxPostgresFixture {
    static final UUID ORGANIZATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final Duration LEASE = Duration.ofSeconds(30);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("relay").withUsername("relay").withPassword("relay");

    DriverManagerDataSource dataSource;
    JdbcTemplate jdbc;
    OutboxRepository repository;

    @BeforeEach
    void createSchema() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE outbox_events");
        repository = new OutboxRepository(jdbc);
    }

    UUID insertEvent(String topic) {
        UUID id = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + id + "\",\"organizationId\":\"" + ORGANIZATION
                + "\",\"aggregateId\":\"" + id + "\",\"aggregateVersion\":1,\"eventType\":\"OrderCreated\"}";
        jdbc.update("""
                INSERT INTO outbox_events (id, organization_id, topic, event_type, aggregate_id,
                    aggregate_version, schema_version, event_key, payload, trace_id, correlation_id, occurred_at)
                VALUES (?, ?, ?, 'OrderCreated', ?, 1, 1, ?, CAST(? AS jsonb), 'test', ?, CURRENT_TIMESTAMP)
                """, id, ORGANIZATION, topic, id, id.toString(), payload, id);
        return id;
    }

    void expire(UUID id) {
        jdbc.update("UPDATE outbox_events SET claimed_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes' WHERE id = ?", id);
    }

    String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, id);
    }
}
