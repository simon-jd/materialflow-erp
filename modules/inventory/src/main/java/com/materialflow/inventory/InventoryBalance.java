package com.materialflow.inventory;

import java.math.BigDecimal;

public record InventoryBalance(
        BigDecimal onHand,
        BigDecimal reserved,
        BigDecimal inTransit,
        long version) {

    public InventoryBalance {
        onHand = nonNegative(onHand, "onHand");
        reserved = nonNegative(reserved, "reserved");
        inTransit = nonNegative(inTransit, "inTransit");
        if (reserved.compareTo(onHand) > 0) {
            throw new InventoryInvariantViolationException("reserved exceeds onHand");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public BigDecimal available() {
        return onHand.subtract(reserved);
    }

    public ReservationResult reserve(BigDecimal requested, boolean allowPartial) {
        requested = positive(requested, "requested");
        BigDecimal allocated = available().min(requested);
        if (!allowPartial && allocated.compareTo(requested) < 0) {
            allocated = BigDecimal.ZERO;
        }
        var next = new InventoryBalance(onHand, reserved.add(allocated), inTransit, version + 1);
        return new ReservationResult(next, allocated, requested.subtract(allocated), next.version());
    }

    public InventoryBalance release(BigDecimal quantity, long fencingToken) {
        quantity = positive(quantity, "quantity");
        requireToken(fencingToken);
        if (reserved.compareTo(quantity) < 0) {
            throw new InventoryInvariantViolationException("release exceeds reserved quantity");
        }
        return new InventoryBalance(onHand, reserved.subtract(quantity), inTransit, version + 1);
    }

    public InventoryBalance ship(BigDecimal quantity, long fencingToken) {
        quantity = positive(quantity, "quantity");
        requireToken(fencingToken);
        if (reserved.compareTo(quantity) < 0) {
            throw new InventoryInvariantViolationException("shipment exceeds reserved quantity");
        }
        return new InventoryBalance(onHand.subtract(quantity), reserved.subtract(quantity), inTransit, version + 1);
    }

    public InventoryBalance receive(BigDecimal quantity) {
        quantity = positive(quantity, "quantity");
        return new InventoryBalance(onHand.add(quantity), reserved, inTransit, version + 1);
    }

    private void requireToken(long fencingToken) {
        if (fencingToken != version) {
            throw new StaleFencingTokenException(version, fencingToken);
        }
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    private static BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    public record ReservationResult(
            InventoryBalance balance,
            BigDecimal allocated,
            BigDecimal shortage,
            long fencingToken) {
    }

    public static final class InventoryInvariantViolationException extends RuntimeException {
        public InventoryInvariantViolationException(String message) { super(message); }
    }

    public static final class StaleFencingTokenException extends RuntimeException {
        public StaleFencingTokenException(long expected, long actual) {
            super("Stale fencing token: expected " + expected + " but got " + actual);
        }
    }
}

