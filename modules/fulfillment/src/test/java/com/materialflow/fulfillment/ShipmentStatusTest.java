package com.materialflow.fulfillment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ShipmentStatusTest {
    @Test
    void statusCanOnlyProgress() {
        assertThatCode(() -> ShipmentStatus.PICKING.requireProgressTo(ShipmentStatus.SHIPPED)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ShipmentStatus.SHIPPED.requireProgressTo(ShipmentStatus.PICKING))
                .isInstanceOf(IllegalStateException.class);
    }
}

