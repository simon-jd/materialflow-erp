package com.materialflow.performance.reservation;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;

/**
 * Reads without a lock and writes with {@code WHERE version = ?}; a lost race retries after a
 * full-jitter exponential backoff, up to {@code maxAttempts} transactions.
 *
 * <p>Under PostgreSQL READ COMMITTED the version-checked UPDATE still waits for a concurrent writer's
 * row lock and then re-evaluates its predicate against the committed row, so a loser pays both the
 * lock wait and a retry. The connection is returned to the pool before backing off.
 */
final class OptimisticRetryStrategy implements ReservationStrategy {
    private final int maxAttempts;
    private final long baseBackoffNanos;
    private final long maxBackoffNanos;

    OptimisticRetryStrategy(int maxAttempts, Duration baseBackoff, Duration maxBackoff) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.baseBackoffNanos = baseBackoff.toNanos();
        this.maxBackoffNanos = maxBackoff.toNanos();
    }

    @Override
    public String name() {
        return "optimistic";
    }

    @Override
    public boolean preventsOversell() {
        return true;
    }

    @Override
    public Attempt reserve(DataSource dataSource, long skuId, BigDecimal quantity) throws SQLException {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (attempt > 0) {
                backOff(attempt);
            }
            Optional<Outcome> decided = InventoryTables.inTransaction(dataSource, connection -> {
                InventoryTables.Balance balance = InventoryTables.read(connection, skuId, false);
                if (!balance.covers(quantity)) {
                    return Optional.of(Outcome.INSUFFICIENT_STOCK);
                }
                BigDecimal reservedAfter = balance.reserved().add(quantity);
                if (!InventoryTables.updateReservedIfVersion(connection, skuId, reservedAfter, balance.version())) {
                    return Optional.empty();
                }
                InventoryTables.appendLedger(connection, skuId, quantity, reservedAfter);
                return Optional.of(Outcome.RESERVED);
            });
            if (decided.isPresent()) {
                return new Attempt(decided.get(), attempt);
            }
        }
        return new Attempt(Outcome.RETRIES_EXHAUSTED, maxAttempts - 1);
    }

    private void backOff(int retry) {
        long ceiling = Math.min(maxBackoffNanos, baseBackoffNanos << Math.min(retry - 1, 20));
        try {
            Thread.sleep(Duration.ofNanos(ThreadLocalRandom.current().nextLong(ceiling + 1)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off", exception);
        }
    }
}
