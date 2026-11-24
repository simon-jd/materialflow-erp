package com.materialflow.performance.reservation;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Harness schema and the SQL shared by all strategies.
 *
 * <p>The balance table mirrors {@code inventory_balances} but deliberately omits its
 * {@code reserved <= on_hand} CHECK constraint: the strategy, not the constraint, has to prevent
 * oversell here, otherwise every strategy would trivially measure zero. Production keeps the
 * constraint as the final guard.
 */
final class InventoryTables {
    private static final String CREATE = """
            DROP TABLE IF EXISTS inventory_ledger, inventory_balance;
            CREATE TABLE inventory_balance (
                id BIGINT PRIMARY KEY,
                on_hand NUMERIC(19,6) NOT NULL,
                reserved NUMERIC(19,6) NOT NULL,
                version BIGINT NOT NULL
            );
            CREATE TABLE inventory_ledger (
                id BIGSERIAL PRIMARY KEY,
                balance_id BIGINT NOT NULL REFERENCES inventory_balance(id),
                quantity NUMERIC(19,6) NOT NULL,
                reserved_after NUMERIC(19,6) NOT NULL
            );
            """;
    private static final String TRUNCATE = "TRUNCATE inventory_ledger, inventory_balance RESTART IDENTITY";
    private static final String INSERT_BALANCE =
            "INSERT INTO inventory_balance (id, on_hand, reserved, version) VALUES (?, ?, 0, 0)";
    private static final String READ = "SELECT on_hand, reserved, version FROM inventory_balance WHERE id = ?";
    private static final String READ_FOR_UPDATE = READ + " FOR UPDATE";
    private static final String UPDATE_IF_VERSION = """
            UPDATE inventory_balance SET reserved = ?, version = version + 1
             WHERE id = ? AND version = ?
            """;
    private static final String INSERT_LEDGER =
            "INSERT INTO inventory_ledger (balance_id, quantity, reserved_after) VALUES (?, ?, ?)";
    private static final String SNAPSHOT = """
            SELECT b.id, b.on_hand, b.reserved,
                   COALESCE(SUM(l.quantity), 0) AS ledger_quantity, COUNT(l.id) AS ledger_rows
              FROM inventory_balance b LEFT JOIN inventory_ledger l ON l.balance_id = b.id
             GROUP BY b.id ORDER BY b.id
            """;

    private InventoryTables() {
    }

    static void create(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE);
            connection.commit();
        }
    }

    /** Recreates SKUs {@code 1..stock.length}; SKU {@code i + 1} starts with {@code stock[i]} on hand. */
    static void reset(DataSource dataSource, long[] stock) throws SQLException {
        inTransaction(dataSource, connection -> {
            try (Statement truncate = connection.createStatement();
                    PreparedStatement insert = connection.prepareStatement(INSERT_BALANCE)) {
                truncate.execute(TRUNCATE);
                for (int index = 0; index < stock.length; index++) {
                    insert.setLong(1, index + 1L);
                    insert.setBigDecimal(2, BigDecimal.valueOf(stock[index]));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        });
    }

    /** Reads the balance; {@code lock} takes the row lock that production's {@code lockInventory} takes. */
    static Balance read(Connection connection, long skuId, boolean lock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(lock ? READ_FOR_UPDATE : READ)) {
            statement.setLong(1, skuId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException("Unknown SKU " + skuId);
                }
                return new Balance(row.getBigDecimal("on_hand"), row.getBigDecimal("reserved"), row.getLong("version"));
            }
        }
    }

    /** Same shape as {@code HotPathMapper.updateInventory}: absolute value, guarded by the read version. */
    static boolean updateReservedIfVersion(Connection connection, long skuId, BigDecimal reserved, long expectedVersion)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_IF_VERSION)) {
            statement.setBigDecimal(1, reserved);
            statement.setLong(2, skuId);
            statement.setLong(3, expectedVersion);
            return statement.executeUpdate() == 1;
        }
    }

    static void appendLedger(Connection connection, long skuId, BigDecimal quantity, BigDecimal reservedAfter)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_LEDGER)) {
            statement.setLong(1, skuId);
            statement.setBigDecimal(2, quantity);
            statement.setBigDecimal(3, reservedAfter);
            statement.executeUpdate();
        }
    }

    static List<SkuState> snapshot(DataSource dataSource) throws SQLException {
        return inTransaction(dataSource, connection -> {
            List<SkuState> states = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(SNAPSHOT)) {
                while (row.next()) {
                    states.add(new SkuState(row.getLong("id"), row.getBigDecimal("on_hand").longValueExact(),
                            row.getBigDecimal("reserved").longValueExact(),
                            row.getBigDecimal("ledger_quantity").longValueExact(), row.getLong("ledger_rows")));
                }
            }
            return states;
        });
    }

    /** Commits when {@code work} returns and rolls back when it throws; the pool disables auto-commit. */
    static <T> T inTransaction(DataSource dataSource, TransactionWork<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @FunctionalInterface
    interface TransactionWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    record Balance(BigDecimal onHand, BigDecimal reserved, long version) {
        boolean covers(BigDecimal quantity) {
            return onHand.subtract(reserved).compareTo(quantity) >= 0;
        }
    }

    record SkuState(long skuId, long onHand, long reserved, long ledgerQuantity, long ledgerRows) {
    }
}
