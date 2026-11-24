package com.materialflow.performance.reservation;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Negative control, never a candidate: read without a lock, then overwrite {@code reserved} without a
 * version check. Concurrent writers overwrite each other, so acknowledged reservations exceed the
 * stored balance. It shows that the harness's invariant checks can detect oversell.
 */
final class UnguardedReadModifyWriteStrategy implements ReservationStrategy {
    private static final String OVERWRITE = "UPDATE inventory_balance SET reserved = ?, version = version + 1 WHERE id = ?";

    @Override
    public String name() {
        return "unguarded-control";
    }

    @Override
    public boolean preventsOversell() {
        return false;
    }

    @Override
    public Attempt reserve(DataSource dataSource, long skuId, BigDecimal quantity) throws SQLException {
        Outcome outcome = InventoryTables.inTransaction(dataSource, connection -> {
            InventoryTables.Balance balance = InventoryTables.read(connection, skuId, false);
            if (!balance.covers(quantity)) {
                return Outcome.INSUFFICIENT_STOCK;
            }
            BigDecimal reservedAfter = balance.reserved().add(quantity);
            try (PreparedStatement statement = connection.prepareStatement(OVERWRITE)) {
                statement.setBigDecimal(1, reservedAfter);
                statement.setLong(2, skuId);
                statement.executeUpdate();
            }
            InventoryTables.appendLedger(connection, skuId, quantity, reservedAfter);
            return Outcome.RESERVED;
        });
        return new Attempt(outcome, 0);
    }
}
