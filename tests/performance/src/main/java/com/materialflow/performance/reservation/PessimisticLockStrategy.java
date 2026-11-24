package com.materialflow.performance.reservation;

import java.math.BigDecimal;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * The production approach ({@code HotPathMapper.lockInventory} + {@code updateInventory}): take the row
 * lock with {@code SELECT ... FOR UPDATE}, decide in Java, then write with a version check that can only
 * fail if the lock was not actually held.
 */
final class PessimisticLockStrategy implements ReservationStrategy {

    @Override
    public String name() {
        return "pessimistic";
    }

    @Override
    public boolean preventsOversell() {
        return true;
    }

    @Override
    public Attempt reserve(DataSource dataSource, long skuId, BigDecimal quantity) throws SQLException {
        Outcome outcome = InventoryTables.inTransaction(dataSource, connection -> {
            InventoryTables.Balance balance = InventoryTables.read(connection, skuId, true);
            if (!balance.covers(quantity)) {
                return Outcome.INSUFFICIENT_STOCK;
            }
            BigDecimal reservedAfter = balance.reserved().add(quantity);
            if (!InventoryTables.updateReservedIfVersion(connection, skuId, reservedAfter, balance.version())) {
                throw new IllegalStateException("Balance version changed while its row lock was held");
            }
            InventoryTables.appendLedger(connection, skuId, quantity, reservedAfter);
            return Outcome.RESERVED;
        });
        return new Attempt(outcome, 0);
    }
}
