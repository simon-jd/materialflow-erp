package com.materialflow.pricing;

public enum PriceSource {
    CONTRACT(0),
    CUSTOMER_SPECIAL(1),
    REGIONAL(2),
    STANDARD(3),
    MANUAL_OVERRIDE(-1);

    private final int priority;

    PriceSource(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}

