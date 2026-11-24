package com.materialflow.performance.reservation;

import java.util.Arrays;

/** Nearest-rank percentiles, matching {@code tests/performance/summarize_jtl.py}. */
record LatencySummary(int samples, double meanMs, double p50Ms, double p95Ms, double p99Ms, double maxMs) {

    static LatencySummary of(long[] nanos) {
        if (nanos.length == 0) {
            return new LatencySummary(0, 0, 0, 0, 0, 0);
        }
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElseThrow();
        return new LatencySummary(sorted.length, millis(mean), millis(nearestRank(sorted, 0.50)),
                millis(nearestRank(sorted, 0.95)), millis(nearestRank(sorted, 0.99)),
                millis(sorted[sorted.length - 1]));
    }

    private static long nearestRank(long[] sorted, double percentile) {
        return sorted[(int) Math.ceil(percentile * sorted.length) - 1];
    }

    static double millis(double nanos) {
        return Math.round(nanos / 1_000.0) / 1_000.0;
    }
}
