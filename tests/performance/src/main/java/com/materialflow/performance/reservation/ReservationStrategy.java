package com.materialflow.performance.reservation;

import java.math.BigDecimal;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Reserves {@code quantity} units of one SKU for one client request, all-or-nothing.
 *
 * <p>Implementations own their transactions. A successful reservation must update the balance and
 * append its ledger row in the same transaction, as the production order path does, so the lock
 * hold time includes the ledger write.
 */
interface ReservationStrategy {

    String name();

    /** False only for the negative control, which is expected to lose updates under contention. */
    boolean preventsOversell();

    Attempt reserve(DataSource dataSource, long skuId, BigDecimal quantity) throws SQLException;

    enum Outcome {
        RESERVED,
        INSUFFICIENT_STOCK,
        /** Optimistic only: every bounded attempt lost the version race. */
        RETRIES_EXHAUSTED,
        /** Assigned by the harness when a request throws instead of returning. */
        ERROR
    }

    /** {@code retries} counts attempts after the first; it is zero for single-attempt strategies. */
    record Attempt(Outcome outcome, int retries) {
    }
}
