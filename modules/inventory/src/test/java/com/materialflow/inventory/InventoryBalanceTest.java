package com.materialflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InventoryBalanceTest {
    @Test
    void partialReservationNeverMakesAvailableNegative() {
        var result = balance("10", "8").reserve(new BigDecimal("5"), true);

        assertThat(result.allocated()).isEqualByComparingTo("2");
        assertThat(result.shortage()).isEqualByComparingTo("3");
        assertThat(result.balance().available()).isZero();
    }

    @Test
    void allOrNothingReservationLeavesBalanceUntouchedOnShortage() {
        var result = balance("10", "8").reserve(new BigDecimal("5"), false);
        assertThat(result.allocated()).isZero();
        assertThat(result.balance().reserved()).isEqualByComparingTo("8");
    }

    @Test
    void staleFencingTokenCannotShip() {
        var result = balance("10", "0").reserve(new BigDecimal("4"), false);
        assertThatThrownBy(() -> result.balance().ship(new BigDecimal("1"), 0))
                .isInstanceOf(InventoryBalance.StaleFencingTokenException.class);
    }

    private InventoryBalance balance(String onHand, String reserved) {
        return new InventoryBalance(new BigDecimal(onHand), new BigDecimal(reserved), BigDecimal.ZERO, 0);
    }
}

