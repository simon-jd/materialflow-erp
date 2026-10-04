package com.materialflow.procurement;

import java.util.Set;

public enum ProcurementStatus {
    SUGGESTED,
    REQUESTED,
    PENDING_APPROVAL,
    APPROVED,
    ORDERED,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CLOSED,
    REJECTED,
    CANCELLED;

    public boolean canTransitionTo(ProcurementStatus target) {
        return switch (this) {
            case SUGGESTED -> Set.of(REQUESTED, CANCELLED).contains(target);
            case REQUESTED -> Set.of(PENDING_APPROVAL, CANCELLED).contains(target);
            case PENDING_APPROVAL -> Set.of(APPROVED, REJECTED, CANCELLED).contains(target);
            case APPROVED -> Set.of(ORDERED, CANCELLED).contains(target);
            case ORDERED -> Set.of(PARTIALLY_RECEIVED, RECEIVED, CANCELLED).contains(target);
            case PARTIALLY_RECEIVED -> Set.of(RECEIVED, CLOSED).contains(target);
            case RECEIVED -> target == CLOSED;
            default -> false;
        };
    }
}

