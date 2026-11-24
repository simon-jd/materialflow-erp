package com.materialflow.performance.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Compares inventory reservation strategies on a real PostgreSQL 17.11 under hot-SKU and spread
 * contention, and writes {@code summary.json}, {@code summary.csv} and {@code runs.json}.
 *
 * <p>Usage: {@code ReservationContentionHarness --output-dir=DIR [--requests=4000] [--repetitions=3]
 * [--workers=8,32,64,128] [--pool-size=20] [--seed=20261123]}. Exits 1 if a strategy that claims to
 * prevent oversell violated the invariant in any run, including warm-ups.
 */
public final class ReservationContentionHarness {
    /** Same image as {@code deploy/compose/compose.yml}. */
    static final String POSTGRES_IMAGE = "postgres:17.11-alpine";
    private static final int OPTIMISTIC_MAX_ATTEMPTS = 10;
    private static final Duration OPTIMISTIC_BASE_BACKOFF = Duration.ofMillis(2);
    private static final Duration OPTIMISTIC_MAX_BACKOFF = Duration.ofMillis(100);
    /** erp-core's {@code spring.datasource.hikari.connection-timeout}. */
    private static final long CONNECTION_TIMEOUT_MS = 3_000;

    private ReservationContentionHarness() {
    }

    public static void main(String[] args) throws Exception {
        if (!run(Options.parse(args))) {
            System.err.println("A strategy that must prevent oversell violated the invariant");
            System.exit(1);
        }
    }

    /** Returns false if a strategy that claims to prevent oversell violated the invariant in any run. */
    static boolean run(Options options) throws Exception {
        List<ReservationStrategy> strategies = List.of(
                new PessimisticLockStrategy(),
                new OptimisticRetryStrategy(OPTIMISTIC_MAX_ATTEMPTS, OPTIMISTIC_BASE_BACKOFF, OPTIMISTIC_MAX_BACKOFF),
                new ConditionalUpdateStrategy(),
                new UnguardedReadModifyWriteStrategy());
        List<Workload> workloads = new ArrayList<>();
        options.workers().forEach(workers -> workloads.add(Workload.hotSku(workers)));
        options.workers().forEach(workers -> workloads.add(Workload.spread(workers)));

        // Testcontainers defaults PostgreSQL to fsync=off; the plain command keeps the Compose defaults.
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE).withCommand("postgres")) {
            postgres.start();
            try (HikariDataSource dataSource = dataSource(postgres, options.poolSize())) {
                InventoryTables.create(dataSource);
                Map<String, Object> environment = environment(dataSource, options);
                List<ContentionRun.Result> runs = new ArrayList<>();
                boolean guardedViolation = false;
                for (Workload workload : workloads) {
                    Workload.Demand warmUp = workload.demand(options.requests(), options.seed());
                    for (ReservationStrategy strategy : strategies) {
                        guardedViolation |= violated(report(ContentionRun.execute(dataSource, strategy, workload, warmUp, 0)));
                    }
                    for (int repetition = 1; repetition <= options.repetitions(); repetition++) {
                        Workload.Demand demand = workload.demand(options.requests(), options.seed() + repetition);
                        for (int offset = 0; offset < strategies.size(); offset++) {
                            ReservationStrategy strategy = strategies.get((offset + repetition) % strategies.size());
                            ContentionRun.Result run = report(
                                    ContentionRun.execute(dataSource, strategy, workload, demand, repetition));
                            guardedViolation |= violated(run);
                            runs.add(run);
                        }
                    }
                }
                write(options.outputDirectory(), environment, runs);
                return !guardedViolation;
            }
        }
    }

    private static HikariDataSource dataSource(PostgreSQLContainer postgres, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("reservation-harness");
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(poolSize);
        // A full pool from the start keeps connection creation out of the measured runs.
        config.setMinimumIdle(poolSize);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        config.setAutoCommit(false);
        return new HikariDataSource(config);
    }

    private static boolean violated(ContentionRun.Result run) {
        return run.preventsOversell() && !run.invariantHolds();
    }

    private static ContentionRun.Result report(ContentionRun.Result run) {
        System.out.printf(Locale.ROOT,
                "%s workers=%d rep=%d %-17s reserved=%d insufficient=%d exhausted=%d errors=%d retries=%d "
                        + "attempts/s=%.1f p50=%.2fms p99=%.2fms oversold=%d invariant=%s%n",
                run.workload(), run.workers(), run.repetition(), run.strategy(), run.reserved(),
                run.insufficientStock(), run.retriesExhausted(), run.errors(), run.retries(), run.attemptsPerSecond(),
                run.latency().p50Ms(), run.latency().p99Ms(), run.oversoldUnits(), run.invariantHolds());
        return run;
    }

    private static Map<String, Object> environment(DataSource dataSource, Options options) throws SQLException {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("generatedAt", Instant.now().toString());
        environment.put("java", System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        environment.put("jvmAvailableProcessors", Runtime.getRuntime().availableProcessors());
        environment.put("postgresImage", POSTGRES_IMAGE);
        environment.put("postgresJdbcDriver", org.postgresql.util.DriverInfo.DRIVER_FULL_NAME);
        environment.put("postgres", InventoryTables.inTransaction(dataSource, connection -> {
            Map<String, String> settings = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement()) {
                for (String query : List.of("SELECT version()", "SHOW fsync", "SHOW synchronous_commit",
                        "SHOW default_transaction_isolation", "SHOW max_connections", "SHOW shared_buffers")) {
                    try (ResultSet row = statement.executeQuery(query)) {
                        row.next();
                        settings.put(query, row.getString(1));
                    }
                }
            }
            return settings;
        }));
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("requestsPerRun", options.requests());
        configuration.put("measuredRepetitions", options.repetitions());
        configuration.put("warmUpRunsPerStrategyAndWorkload", 1);
        configuration.put("workers", options.workers());
        configuration.put("workerModel", "one virtual thread per worker, closed loop, no think time");
        configuration.put("hikariMaximumPoolSize", options.poolSize());
        configuration.put("hikariConnectionTimeoutMs", CONNECTION_TIMEOUT_MS);
        configuration.put("quantityPerRequest", "uniform 1.." + Workload.MAX_QUANTITY);
        configuration.put("stockPerSku", "floor(demand on that SKU / 2)");
        configuration.put("seed", options.seed());
        configuration.put("optimisticRetry", Map.of("maxAttempts", OPTIMISTIC_MAX_ATTEMPTS,
                "backoff", "full jitter, base " + OPTIMISTIC_BASE_BACKOFF.toMillis() + " ms doubling to "
                        + OPTIMISTIC_MAX_BACKOFF.toMillis() + " ms cap"));
        configuration.put("strategyOrder", "rotated by one position per repetition");
        environment.put("configuration", configuration);
        return environment;
    }

    private static void write(Path directory, Map<String, Object> environment, List<ContentionRun.Result> runs)
            throws IOException {
        Files.createDirectories(directory);
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        List<CellSummary> summary = CellSummary.of(runs);
        Map<String, Object> document = new LinkedHashMap<>(environment);
        document.put("summary", summary);
        json.writeValue(directory.resolve("summary.json").toFile(), document);
        json.writeValue(directory.resolve("runs.json").toFile(), runs);
        List<String> csv = new ArrayList<>();
        csv.add(CellSummary.CSV_HEADER);
        summary.forEach(cell -> csv.add(cell.csvRow()));
        Files.write(directory.resolve("summary.csv"), csv, StandardCharsets.UTF_8);
    }

    /** Median across measured repetitions of one (workload, workers, strategy) cell; counts are totals. */
    record CellSummary(
            String workload,
            int workers,
            String strategy,
            int repetitions,
            double medianAttemptsPerSecond,
            double minAttemptsPerSecond,
            double maxAttemptsPerSecond,
            double medianReservationsPerSecond,
            double medianP50Ms,
            double medianP95Ms,
            double medianP99Ms,
            long reserved,
            long insufficientStock,
            long retries,
            long retriesExhausted,
            long errors,
            long oversoldUnits,
            double medianUnsoldUnits,
            long runsViolatingInvariant) {
        static final String CSV_HEADER = "workload,workers,strategy,repetitions,median_attempts_per_s,"
                + "min_attempts_per_s,max_attempts_per_s,median_reservations_per_s,median_p50_ms,median_p95_ms,"
                + "median_p99_ms,reserved,insufficient_stock,retries,retries_exhausted,errors,oversold_units,"
                + "median_unsold_units,runs_violating_invariant";

        static List<CellSummary> of(List<ContentionRun.Result> runs) {
            Map<String, List<ContentionRun.Result>> cells = runs.stream().collect(Collectors.groupingBy(
                    run -> run.workload() + "|" + run.workers() + "|" + run.strategy(), LinkedHashMap::new,
                    Collectors.toList()));
            return cells.values().stream().map(CellSummary::summarize).toList();
        }

        private static CellSummary summarize(List<ContentionRun.Result> cell) {
            ContentionRun.Result first = cell.getFirst();
            return new CellSummary(first.workload(), first.workers(), first.strategy(), cell.size(),
                    median(cell, ContentionRun.Result::attemptsPerSecond),
                    cell.stream().mapToDouble(ContentionRun.Result::attemptsPerSecond).min().orElseThrow(),
                    cell.stream().mapToDouble(ContentionRun.Result::attemptsPerSecond).max().orElseThrow(),
                    median(cell, ContentionRun.Result::reservationsPerSecond),
                    median(cell, run -> run.latency().p50Ms()), median(cell, run -> run.latency().p95Ms()),
                    median(cell, run -> run.latency().p99Ms()),
                    cell.stream().mapToLong(ContentionRun.Result::reserved).sum(),
                    cell.stream().mapToLong(ContentionRun.Result::insufficientStock).sum(),
                    cell.stream().mapToLong(ContentionRun.Result::retries).sum(),
                    cell.stream().mapToLong(ContentionRun.Result::retriesExhausted).sum(),
                    cell.stream().mapToLong(ContentionRun.Result::errors).sum(),
                    cell.stream().mapToLong(ContentionRun.Result::oversoldUnits).sum(),
                    median(cell, ContentionRun.Result::unsoldUnits),
                    cell.stream().filter(run -> !run.invariantHolds()).count());
        }

        private static double median(List<ContentionRun.Result> cell, ToDoubleFunction<ContentionRun.Result> metric) {
            double[] values = cell.stream().mapToDouble(metric).sorted().toArray();
            int middle = values.length / 2;
            return values.length % 2 == 1 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
        }

        String csvRow() {
            return String.format(Locale.ROOT, "%s,%d,%s,%d,%.1f,%.1f,%.1f,%.1f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%.1f,%d",
                    workload, workers, strategy, repetitions, medianAttemptsPerSecond, minAttemptsPerSecond,
                    maxAttemptsPerSecond, medianReservationsPerSecond, medianP50Ms, medianP95Ms, medianP99Ms, reserved,
                    insufficientStock, retries, retriesExhausted, errors, oversoldUnits, medianUnsoldUnits,
                    runsViolatingInvariant);
        }
    }

    record Options(Path outputDirectory, int requests, int repetitions, List<Integer> workers, int poolSize, long seed) {

        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>(Map.of(
                    "requests", "4000", "repetitions", "3", "workers", "8,32,64,128", "pool-size", "20",
                    "seed", "20261123"));
            for (String argument : args) {
                int separator = argument.indexOf('=');
                if (!argument.startsWith("--") || separator < 0) {
                    throw new IllegalArgumentException("Expected --name=value but got " + argument);
                }
                values.put(argument.substring(2, separator), argument.substring(separator + 1));
            }
            String output = values.get("output-dir");
            if (output == null) {
                throw new IllegalArgumentException("--output-dir is required");
            }
            List<Integer> workers = Arrays.stream(values.get("workers").split(","))
                    .map(String::trim).map(Integer::valueOf).toList();
            return new Options(Path.of(output), Integer.parseInt(values.get("requests")),
                    Integer.parseInt(values.get("repetitions")), workers, Integer.parseInt(values.get("pool-size")),
                    Long.parseLong(values.get("seed")));
        }
    }
}
