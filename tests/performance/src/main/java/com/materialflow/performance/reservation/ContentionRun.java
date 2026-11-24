package com.materialflow.performance.reservation;

import com.materialflow.performance.reservation.ReservationStrategy.Outcome;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;
import javax.sql.DataSource;

/**
 * Executes one demand against one strategy with a fixed number of closed-loop workers, then checks the
 * no-oversell invariant against what the clients were told.
 *
 * <p>Each worker is a virtual thread that takes the next request, runs it to completion and takes
 * another, with no think time. Latency is measured around the whole request, so it includes waiting
 * for a pooled connection; with more workers than connections that queue is part of the result, as it
 * is for Tomcat request threads in erp-core.
 */
final class ContentionRun {

    private ContentionRun() {
    }

    static Result execute(DataSource dataSource, ReservationStrategy strategy, Workload workload,
            Workload.Demand demand, int repetition) throws SQLException, InterruptedException {
        InventoryTables.reset(dataSource, demand.stockBySku());
        List<Workload.Request> requests = demand.requests();
        int total = requests.size();
        long[] latencyNanos = new long[total];
        Outcome[] outcomes = new Outcome[total];
        AtomicLongArray acknowledgedBySku = new AtomicLongArray(demand.stockBySku().length);
        LongAdder retries = new LongAdder();
        Map<String, LongAdder> errorsBySqlState = new ConcurrentHashMap<>();
        AtomicInteger cursor = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        long elapsedNanos;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> workers = new ArrayList<>();
            for (int worker = 0; worker < workload.workers(); worker++) {
                workers.add(executor.submit(() -> {
                    start.await();
                    for (int index = cursor.getAndIncrement(); index < total; index = cursor.getAndIncrement()) {
                        Workload.Request request = requests.get(index);
                        long started = System.nanoTime();
                        Outcome outcome;
                        try {
                            ReservationStrategy.Attempt attempt =
                                    strategy.reserve(dataSource, request.skuId(), request.quantity());
                            outcome = attempt.outcome();
                            retries.add(attempt.retries());
                            if (outcome == Outcome.RESERVED) {
                                acknowledgedBySku.addAndGet((int) request.skuId() - 1,
                                        request.quantity().longValueExact());
                            }
                        } catch (SQLException exception) {
                            outcome = Outcome.ERROR;
                            // Pool acquisition timeouts carry no SQLState; name them by type instead.
                            String kind = exception.getSQLState() != null
                                    ? exception.getSQLState() : exception.getClass().getSimpleName();
                            errorsBySqlState.computeIfAbsent(kind, state -> new LongAdder()).increment();
                        }
                        latencyNanos[index] = System.nanoTime() - started;
                        outcomes[index] = outcome;
                    }
                    return null;
                }));
            }
            long begin = System.nanoTime();
            start.countDown();
            for (Future<?> worker : workers) {
                try {
                    worker.get();
                } catch (ExecutionException exception) {
                    throw new IllegalStateException("Worker failed outside the SQL error path", exception.getCause());
                }
            }
            elapsedNanos = System.nanoTime() - begin;
        }

        Map<String, Long> errors = new TreeMap<>();
        errorsBySqlState.forEach((state, count) -> errors.put(state, count.sum()));
        return Result.of(workload, strategy, repetition, demand, outcomes, latencyNanos, elapsedNanos,
                retries.sum(), errors, acknowledgedBySku, InventoryTables.snapshot(dataSource));
    }

    /** One measured run. Units are reservation quantities; {@code invariantHolds} is the pass/fail verdict. */
    record Result(
            String workload,
            int skuCount,
            int workers,
            String strategy,
            boolean preventsOversell,
            int repetition,
            int requests,
            int reserved,
            int insufficientStock,
            int retriesExhausted,
            int errors,
            Map<String, Long> errorsBySqlState,
            long retries,
            long stockUnits,
            long acknowledgedUnits,
            long reservedUnits,
            long ledgerUnits,
            long unsoldUnits,
            long oversoldUnits,
            long acknowledgedVsReservedMismatchUnits,
            long ledgerVsReservedMismatchUnits,
            int skusReservedAboveOnHand,
            boolean invariantHolds,
            double elapsedMs,
            double attemptsPerSecond,
            double reservationsPerSecond,
            LatencySummary latency,
            LatencySummary reservedLatency) {

        static Result of(Workload workload, ReservationStrategy strategy, int repetition, Workload.Demand demand,
                Outcome[] outcomes, long[] latencyNanos, long elapsedNanos, long retries,
                Map<String, Long> errorsBySqlState, AtomicLongArray acknowledgedBySku,
                List<InventoryTables.SkuState> skus) {
            long acknowledged = 0;
            long reservedUnits = 0;
            long ledgerUnits = 0;
            long ledgerRows = 0;
            long unsold = 0;
            long oversold = 0;
            long acknowledgedMismatch = 0;
            long ledgerMismatch = 0;
            int aboveOnHand = 0;
            for (InventoryTables.SkuState sku : skus) {
                long promised = acknowledgedBySku.get((int) sku.skuId() - 1);
                acknowledged += promised;
                reservedUnits += sku.reserved();
                ledgerUnits += sku.ledgerQuantity();
                ledgerRows += sku.ledgerRows();
                unsold += Math.max(0, sku.onHand() - sku.reserved());
                oversold += Math.max(0, promised - sku.onHand());
                acknowledgedMismatch += Math.abs(promised - sku.reserved());
                ledgerMismatch += Math.abs(sku.ledgerQuantity() - sku.reserved());
                if (sku.reserved() > sku.onHand()) {
                    aboveOnHand++;
                }
            }
            int reserved = count(outcomes, Outcome.RESERVED);
            boolean holds = oversold == 0 && acknowledgedMismatch == 0 && ledgerMismatch == 0
                    && aboveOnHand == 0 && ledgerRows == reserved;
            double seconds = elapsedNanos / 1e9;
            long[] reservedLatency = IntStream.range(0, outcomes.length)
                    .filter(index -> outcomes[index] == Outcome.RESERVED)
                    .mapToLong(index -> latencyNanos[index]).toArray();
            return new Result(workload.name(), workload.skuCount(), workload.workers(), strategy.name(),
                    strategy.preventsOversell(), repetition, outcomes.length, reserved,
                    count(outcomes, Outcome.INSUFFICIENT_STOCK), count(outcomes, Outcome.RETRIES_EXHAUSTED),
                    count(outcomes, Outcome.ERROR), errorsBySqlState, retries, demand.stockUnits(), acknowledged,
                    reservedUnits, ledgerUnits, unsold, oversold, acknowledgedMismatch, ledgerMismatch, aboveOnHand,
                    holds, LatencySummary.millis(elapsedNanos), round(outcomes.length / seconds),
                    round(reserved / seconds), LatencySummary.of(latencyNanos), LatencySummary.of(reservedLatency));
        }

        private static int count(Outcome[] outcomes, Outcome wanted) {
            int count = 0;
            for (Outcome outcome : outcomes) {
                if (outcome == wanted) {
                    count++;
                }
            }
            return count;
        }

        private static double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }
    }
}
