package com.materialflow.relay.outbox;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public List<OutboxEvent> claim(
            String instanceId,
            int batchSize,
            int maxAttempts,
            Duration claimTimeout) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT id
                      FROM outbox_events
                     WHERE attempt_count < ?
                       AND ((status IN ('PENDING', 'FAILED') AND next_attempt_at <= CURRENT_TIMESTAMP)
                         OR (status = 'CLAIMED'
                             AND claimed_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 millisecond')))
                     ORDER BY occurred_at, id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                )
                UPDATE outbox_events event
                   SET status = 'CLAIMED', claimed_by = ?, claimed_at = CURRENT_TIMESTAMP,
                       attempt_count = event.attempt_count + 1, last_error = NULL,
                       claim_token = gen_random_uuid()
                  FROM candidates
                 WHERE event.id = candidates.id
                RETURNING event.id, event.topic, event.event_key, event.payload::text,
                          event.trace_parent, event.attempt_count, event.occurred_at, event.claim_token
                """, (resultSet, rowNumber) -> new OutboxEvent(
                        resultSet.getObject(1, UUID.class), resultSet.getString(2), resultSet.getString(3),
                        resultSet.getString(4), resultSet.getString(5), resultSet.getInt(6),
                        resultSet.getTimestamp(7).toInstant(), resultSet.getObject(8, UUID.class)),
                maxAttempts, claimTimeout.toMillis(), batchSize, instanceId);
    }

    @Transactional
    public boolean markPublished(UUID eventId, String instanceId, UUID claimToken) {
        return jdbc.update("""
                UPDATE outbox_events
                   SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                       claimed_by = NULL, claimed_at = NULL, claim_token = NULL, last_error = NULL
                 WHERE id = ? AND status = 'CLAIMED' AND claimed_by = ? AND claim_token = ?
                """, eventId, instanceId, claimToken) == 1;
    }

    @Transactional
    public boolean markFailed(
            UUID eventId,
            String instanceId,
            UUID claimToken,
            int attemptCount,
            int maxAttempts,
            Instant nextAttemptAt,
            String error) {
        String status = attemptCount >= maxAttempts ? "DEAD_LETTER" : "FAILED";
        return jdbc.update("""
                UPDATE outbox_events
                   SET status = ?, next_attempt_at = ?, claimed_by = NULL, claimed_at = NULL,
                       claim_token = NULL, last_error = ?
                 WHERE id = ? AND status = 'CLAIMED' AND claimed_by = ? AND claim_token = ?
                   AND attempt_count = ?
                """, status, Timestamp.from(nextAttemptAt), truncate(error), eventId, instanceId,
                claimToken, attemptCount) == 1;
    }

    @Transactional
    public int deadLetterExhausted(int maxAttempts, Duration claimTimeout, int limit) {
        return jdbc.update("""
                WITH exhausted AS (
                    SELECT id FROM outbox_events
                     WHERE attempt_count >= ?
                       AND ((status = 'CLAIMED'
                             AND claimed_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 millisecond'))
                         OR (status IN ('PENDING', 'FAILED') AND next_attempt_at <= CURRENT_TIMESTAMP))
                     ORDER BY occurred_at, id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                )
                UPDATE outbox_events event
                   SET status = 'DEAD_LETTER', claimed_by = NULL, claimed_at = NULL, claim_token = NULL,
                       last_error = 'Retry budget exhausted; delivery may have occurred; inspect before replay'
                  FROM exhausted WHERE event.id = exhausted.id
                """, maxAttempts, claimTimeout.toMillis(), limit);
    }

    public long deadLetterCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status = 'DEAD_LETTER'",
                Long.class);
        return count == null ? 0 : count;
    }

    public Backlog backlog() {
        return jdbc.query("""
                SELECT count(*), COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - min(occurred_at))), 0)
                  FROM outbox_events WHERE status IN ('PENDING', 'FAILED', 'CLAIMED')
                """, resultSet -> {
                    resultSet.next();
                    return new Backlog(resultSet.getLong(1), resultSet.getDouble(2));
                });
    }

    private String truncate(String error) {
        String safe = error == null ? "Unknown Kafka publish failure" : error;
        return safe.length() <= 1000 ? safe : safe.substring(0, 1000);
    }

    public record Backlog(long count, double oldestAgeSeconds) {
    }
}
