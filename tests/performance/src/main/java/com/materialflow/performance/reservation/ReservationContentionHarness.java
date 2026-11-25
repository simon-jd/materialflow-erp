package com.materialflow.performance.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
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
 * [--workers=8,32,64,128] [--pool-size=20] [--seed=20261123] [--jdbc-url=URL --jdbc-user=USER]
 * [--rtt-samples=200]}. Exits 1 if a strategy that claims to prevent oversell violated the invariant
 * in any run, including warm-ups.
 *
 * <p>Without {@code --jdbc-url} it starts a disposable Testcontainers PostgreSQL. With it, it uses an
 * existing, dedicated database (for example a managed server), whose password comes from the
 * {@value #PASSWORD_ENVIRONMENT} environment variable, never from the command line. The harness drops
 * and recreates its two tables there, so never point it at an application database. Either way it
 * first measures the client-to-server round trip ({@code SELECT 1} on a pooled connection).
 */
public final class ReservationContentionHarness {
    /** Same image as {@code deploy/compose/compose.yml}. */
    static final String POSTGRES_IMAGE = "postgres:17.11-alpine";
    private static final int OPTIMISTIC_MAX_ATTEMPTS = 10;
    private static final Duration OPTIMISTIC_BASE_BACKOFF = Duration.ofMillis(2);
    private static final Duration OPTIMISTIC_MAX_BACKOFF = Duration.ofMillis(100);
    /** erp-core's {@code spring.datasource.hikari.connection-timeout}. */
    private static final long CONNECTION_TIMEOUT_MS = 3_000;
    static final String PASSWORD_ENVIRONMENT = "MATERIALFLOW_HARNESS_JDBC_PASSWORD";

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

        if (options.jdbcUrl() != null) {
            String password = System.getenv(PASSWORD_ENVIRONMENT);
            if (password == null || password.isEmpty()) {
                throw new IllegalArgumentException("--jdbc-url needs the password in " + PASSWORD_ENVIRONMENT);
            }
            try (HikariDataSource dataSource = dataSource(options.jdbcUrl(), options.jdbcUser(), password,
                    options.poolSize())) {
                return measure(dataSource, strategies, workloads, options, "external JDBC database");
            }
        }
        // Testcontainers defaults PostgreSQL to fsync=off; the plain command keeps the Compose defaults.
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE).withCommand("postgres")) {
            postgres.start();
            try (HikariDataSource dataSource = dataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword(), options.poolSize())) {
                return measure(dataSource, strategies, workloads, options, "Testcontainers " + POSTGRES_IMAGE);
            }
        }
    }

    private static boolean measure(HikariDataSource dataSource, List<ReservationStrategy> strategies,
            List<Workload> workloads, Options options, String target) throws Exception {
        InventoryTables.create(dataSource);
        Map<String, Object> environment = environment(dataSource, options, target);
        System.out.printf(Locale.ROOT, "target=%s roundTrip=%s%n", target, environment.get("roundTripBefore"));
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
        environment.put("roundTripAfter", roundTrip(dataSource, options.rttSamples()));
        write(options.outputDirectory(), environment, runs);
        return !guardedViolation;
    }

    /** Latency of {@code SELECT 1} on one pooled connection: one network round trip plus a trivial query. */
    static Map<String, Object> roundTrip(DataSource dataSource, int samples) throws SQLException {
        double[] millis = new double[samples];
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (int warmUp = 0; warmUp < 20; warmUp++) {
                statement.executeQuery("SELECT 1").close();
            }
            for (int index = 0; index < samples; index++) {
                long started = System.nanoTime();
                statement.executeQuery("SELECT 1").close();
                millis[index] = (System.nanoTime() - started) / 1_000_000.0;
            }
            connection.rollback();
        }
        Arrays.sort(millis);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("samples", samples);
        result.put("minMs", millis[0]);
        result.put("p50Ms", nearestRank(millis, 50));
        result.put("p95Ms", nearestRank(millis, 95));
        result.put("p99Ms", nearestRank(millis, 99));
        result.put("maxMs", millis[samples - 1]);
        return result;
    }

    static double nearestRank(double[] sorted, int percentile) {
        int rank = (int) Math.ceil(sorted.length * percentile / 100.0);
        return sorted[Math.max(rank - 1, 0)];
    }

    private static HikariDataSource dataSource(String jdbcUrl, String user, String password, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("reservation-harness");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
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

    private static Map<String, Object> environment(DataSource dataSource, Options options, String target)
            throws SQLException {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("generatedAt", Instant.now().toString());
        environment.put("target", target);
        environment.put("java", System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        environment.put("jvmAvailableProcessors", Runtime.getRuntime().availableProcessors());
        if (options.jdbcUrl() == null) {
            environment.put("postgresImage", POSTGRES_IMAGE);
        } else {
            // Host and database only: user and password stay out of the evidence.
            environment.put("jdbcUrl", options.jdbcUrl().replaceAll("[?].*$", ""));
        }
        environment.put("postgresJdbcDriver", org.postgresql.util.DriverInfo.DRIVER_FULL_NAME);
        environment.put("postgres", InventoryTables.inTransaction(dataSource, connection -> {
            Map<String, String> settings = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement()) {
                for (String query : List.of("SELECT version()", "SHOW fsync", "SHOW synchronous_commit",
                        "SHOW default_transaction_isolation", "SHOW max_connections", "SHOW shared_buffers",
                        "SELECT current_database()", "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
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
        environment.put("roundTripBefore", roundTrip(dataSource, options.rttSamples()));
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

    record Options(Path outputDirectory, int requests, int repetitions, List<Integer> workers, int poolSize, long seed,
            String jdbcUrl, String jdbcUser, int rttSamples) {

        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>(Map.of(
                    "requests", "4000", "repetitions", "3", "workers", "8,32,64,128", "pool-size", "20",
                    "seed", "20261123", "rtt-samples", "200"));
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
            String jdbcUrl = values.get("jdbc-url");
            if (jdbcUrl != null && (!jdbcUrl.startsWith("jdbc:postgresql://") || values.get("jdbc-user") == null)) {
                throw new IllegalArgumentException("--jdbc-url must be a jdbc:postgresql:// URL and needs --jdbc-user");
            }
            if (values.containsKey("jdbc-password")) {
                throw new IllegalArgumentException("Pass the password in " + PASSWORD_ENVIRONMENT + ", not as an argument");
            }
            int rttSamples = Integer.parseInt(values.get("rtt-samples"));
            if (rttSamples < 1) {
                throw new IllegalArgumentException("--rtt-samples must be at least 1");
            }
            return new Options(Path.of(output), Integer.parseInt(values.get("requests")),
                    Integer.parseInt(values.get("repetitions")), workers, Integer.parseInt(values.get("pool-size")),
                    Long.parseLong(values.get("seed")), jdbcUrl, values.get("jdbc-user"), rttSamples);
        }
    }
}
