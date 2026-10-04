package com.materialflow.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OrderStatusTest {
    @Test
    void permitsDocumentedHappyPathAndRejectsBackwardMovement() {
        assertThat(OrderStatus.DRAFT.canTransitionTo(OrderStatus.SUBMITTED)).isTrue();
        assertThat(OrderStatus.APPROVED.canTransitionTo(OrderStatus.RESERVED)).isTrue();
        assertThat(OrderStatus.FULFILLED.canTransitionTo(OrderStatus.INVOICED)).isTrue();
        assertThatThrownBy(() -> OrderStatus.FULFILLED.requireTransitionTo(OrderStatus.RESERVED))
                .isInstanceOf(OrderStatus.IllegalStateTransitionException.class);
    }
}

