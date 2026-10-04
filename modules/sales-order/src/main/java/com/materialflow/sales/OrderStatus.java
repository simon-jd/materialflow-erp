package com.materialflow.sales;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum OrderStatus {
    DRAFT,
    SUBMITTED,
    PENDING_APPROVAL,
    APPROVED,
    RESERVED,
    PARTIALLY_ALLOCATED,
    BACKORDERED,
    PARTIALLY_FULFILLED,
    FULFILLED,
    INVOICED,
    SETTLED,
    REJECTED,
    CANCELLED,
    EXPIRED,
    RECONCILIATION_EXCEPTION;

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(DRAFT, EnumSet.of(SUBMITTED, CANCELLED)),
            Map.entry(SUBMITTED, EnumSet.of(PENDING_APPROVAL, APPROVED, CANCELLED)),
            Map.entry(PENDING_APPROVAL, EnumSet.of(APPROVED, REJECTED, CANCELLED, EXPIRED)),
            Map.entry(APPROVED, EnumSet.of(RESERVED, PARTIALLY_ALLOCATED, BACKORDERED, CANCELLED)),
            Map.entry(RESERVED, EnumSet.of(PARTIALLY_FULFILLED, FULFILLED, CANCELLED, EXPIRED)),
            Map.entry(PARTIALLY_ALLOCATED, EnumSet.of(RESERVED, PARTIALLY_FULFILLED, BACKORDERED, CANCELLED)),
            Map.entry(BACKORDERED, EnumSet.of(PARTIALLY_ALLOCATED, RESERVED, CANCELLED, EXPIRED)),
            Map.entry(PARTIALLY_FULFILLED, EnumSet.of(FULFILLED, INVOICED, RECONCILIATION_EXCEPTION)),
            Map.entry(FULFILLED, EnumSet.of(INVOICED, RECONCILIATION_EXCEPTION)),
            Map.entry(INVOICED, EnumSet.of(SETTLED, RECONCILIATION_EXCEPTION)),
            Map.entry(RECONCILIATION_EXCEPTION, EnumSet.of(INVOICED, SETTLED)));

    public boolean canTransitionTo(OrderStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public void requireTransitionTo(OrderStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException(this, target);
        }
    }

    public static final class IllegalStateTransitionException extends RuntimeException {
        public IllegalStateTransitionException(OrderStatus source, OrderStatus target) {
            super("Order cannot transition from " + source + " to " + target);
        }
    }
}

