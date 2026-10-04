package com.materialflow.fulfillment;

public enum ShipmentStatus {
    PLANNED(0),
    PICKING(1),
    PARTIALLY_SHIPPED(2),
    SHIPPED(3),
    PARTIALLY_DELIVERED(4),
    DELIVERED(5),
    CANCELLED(99);

    private final int sequence;

    ShipmentStatus(int sequence) {
        this.sequence = sequence;
    }

    public void requireProgressTo(ShipmentStatus target) {
        if (this == CANCELLED || target.sequence < sequence) {
            throw new IllegalStateException("Shipment status cannot move backward");
        }
    }
}

