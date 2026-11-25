package com.materialflow.performance.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReservationContentionHarnessOptionsTest {

    @Test
    void defaultsUseTestcontainersAndTheLocalRunSettings() {
        ReservationContentionHarness.Options options = ReservationContentionHarness.Options.parse(
                new String[] {"--output-dir=out"});
        assertEquals(Path.of("out"), options.outputDirectory());
        assertEquals(List.of(8, 32, 64, 128), options.workers());
        assertEquals(4000, options.requests());
        assertEquals(20, options.poolSize());
        assertEquals(200, options.rttSamples());
        assertNull(options.jdbcUrl());
    }

    @Test
    void externalDatabaseNeedsAPostgresUrlAndAUserButNeverAPasswordArgument() {
        ReservationContentionHarness.Options options = ReservationContentionHarness.Options.parse(new String[] {
            "--output-dir=out", "--jdbc-url=jdbc:postgresql://db.internal:5432/harness?sslmode=require",
            "--jdbc-user=harness", "--rtt-samples=50"});
        assertEquals("harness", options.jdbcUser());
        assertEquals(50, options.rttSamples());
        assertThrows(IllegalArgumentException.class, () -> ReservationContentionHarness.Options.parse(
                new String[] {"--output-dir=out", "--jdbc-url=jdbc:postgresql://db.internal:5432/harness"}));
        assertThrows(IllegalArgumentException.class, () -> ReservationContentionHarness.Options.parse(
                new String[] {"--output-dir=out", "--jdbc-url=jdbc:mysql://db:3306/h", "--jdbc-user=u"}));
        assertThrows(IllegalArgumentException.class, () -> ReservationContentionHarness.Options.parse(new String[] {
            "--output-dir=out", "--jdbc-url=jdbc:postgresql://db:5432/h", "--jdbc-user=u", "--jdbc-password=x"}));
        assertThrows(IllegalArgumentException.class, () -> ReservationContentionHarness.Options.parse(
                new String[] {"--output-dir=out", "--rtt-samples=0"}));
    }

    @Test
    void nearestRankPercentiles() {
        double[] sorted = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertEquals(5, ReservationContentionHarness.nearestRank(sorted, 50));
        assertEquals(10, ReservationContentionHarness.nearestRank(sorted, 95));
        assertEquals(10, ReservationContentionHarness.nearestRank(sorted, 99));
        assertEquals(7, ReservationContentionHarness.nearestRank(new double[] {7}, 50));
    }
}
