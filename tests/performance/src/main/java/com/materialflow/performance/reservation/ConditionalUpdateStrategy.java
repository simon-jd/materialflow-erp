package com.materialflow.performance.reservation;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * One guarded statement: the availability check and the increment happen atomically in PostgreSQL.
 * A blocked UPDATE re-evaluates the predicate against the committed row, so no retry is needed.
 * {@code RETURNING} supplies the post-update balance for the ledger row written in the same transaction.
 */
final class ConditionalUpdateStrategy implements ReservationStrategy {
    private static final String RESERVE = """
            UPDATE inventory_balance SET reserved = reserved + ?, version = version + 1
             WHERE id = ? AND on_hand - reserved >= ?
            RETURNING reserved
            """;

    @Override
    public String name() {
        return "conditional";
    }

    @Override
    public boolean preventsOversell() {
        return true;
    }

    @Override
    public Attempt reserve(DataSource dataSource, long skuId, BigDecimal quantity) throws SQLException {
        Outcome outcome = InventoryTables.inTransaction(dataSource, connection -> {
            BigDecimal reservedAfter;
            try (PreparedStatement statement = connection.prepareStatement(RESERVE)) {
                statement.setBigDecimal(1, quantity);
                statement.setLong(2, skuId);
                statement.setBigDecimal(3, quantity);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) {
                        return Outcome.INSUFFICIENT_STOCK;
                    }
                    reservedAfter = row.getBigDecimal(1);
                }
            }
            InventoryTables.appendLedger(connection, skuId, quantity, reservedAfter);
            return Outcome.RESERVED;
        });
        return new Attempt(outcome, 0);
    }
}
