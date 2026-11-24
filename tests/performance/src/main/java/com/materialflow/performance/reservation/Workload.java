package com.materialflow.performance.reservation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/** A contention shape: how many SKUs share the demand and how many closed-loop workers submit it. */
record Workload(String name, int skuCount, int workers) {
    static final int MAX_QUANTITY = 5;

    static Workload hotSku(int workers) {
        return new Workload("hot-sku", 1, workers);
    }

    static Workload spread(int workers) {
        return new Workload("spread-100-skus", 100, workers);
    }

    /**
     * Generates {@code requests} reservations of 1..{@value #MAX_QUANTITY} units on uniformly chosen SKUs.
     * Each SKU's stock covers half of its own demand, so every run crosses the sold-out boundary. The
     * seed makes every strategy in a repetition face the identical request sequence.
     */
    Demand demand(int requests, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        List<Request> generated = new ArrayList<>(requests);
        long[] demandBySku = new long[skuCount];
        for (int index = 0; index < requests; index++) {
            int sku = random.nextInt(skuCount);
            int quantity = 1 + random.nextInt(MAX_QUANTITY);
            generated.add(new Request(sku + 1L, BigDecimal.valueOf(quantity)));
            demandBySku[sku] += quantity;
        }
        return new Demand(List.copyOf(generated), Arrays.stream(demandBySku).map(units -> units / 2).toArray());
    }

    record Request(long skuId, BigDecimal quantity) {
    }

    /** {@code stockBySku[i]} is the on-hand quantity of SKU {@code i + 1}. */
    record Demand(List<Request> requests, long[] stockBySku) {
        long stockUnits() {
            return Arrays.stream(stockBySku).sum();
        }
    }
}
