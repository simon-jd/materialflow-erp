package com.materialflow.erp.events;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fault-injection gate used only by the process-kill drill. A record armed with the window's
 * header waits on the PostgreSQL advisory lock whose key is the header value. The drill holds
 * that lock, sees this session waiting in {@code pg_locks}, SIGKILLs the JVM and only then
 * releases the lock, so the record redelivered after restart passes straight through.
 * Unarmed records are never delayed.
 */
final class AdvisoryLockCrashWindowGate implements ConsumerCrashWindowGate {
    private static final Logger LOG = LoggerFactory.getLogger(AdvisoryLockCrashWindowGate.class);
    private final JdbcTemplate jdbc;

    AdvisoryLockCrashWindowGate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enter(Window window, ConsumerRecord<?, ?> record) {
        Header armed = record.headers().lastHeader(window.header());
        if (armed == null) return;
        long lockKey = Long.parseLong(new String(armed.value(), StandardCharsets.UTF_8));
        LOG.warn("Fault injection: holding {}-{}@{} in {} on advisory lock {}",
                record.topic(), record.partition(), record.offset(), window, lockKey);
        // Auto-commit statement: granted once the drill releases its lock, then freed immediately.
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(?)", Integer.class, lockKey);
    }
}
