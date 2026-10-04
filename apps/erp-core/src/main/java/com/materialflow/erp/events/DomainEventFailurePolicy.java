package com.materialflow.erp.events;

import java.sql.SQLException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

final class DomainEventFailurePolicy {
    private DomainEventFailurePolicy() {
    }

    static String reason(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidEventEnvelopeException) return "invalid";
            // The current JDBC translator leaves PostgreSQL lock_not_available
            // uncategorized. Match stable SQLSTATE, never localized error text.
            if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) return "database";
            if (cause instanceof TransientDataAccessException
                    || cause instanceof RecoverableDataAccessException
                    || cause instanceof CannotGetJdbcConnectionException
                    || cause instanceof CannotCreateTransactionException) return "database";
        }
        return "other";
    }

    static BackOff backOff(Exception exception) {
        if (!"database".equals(reason(exception))) return null;
        // Keep valid records in Kafka during database outages; the interval, not the
        // attempt count, is bounded. Operators must monitor lag and retention headroom.
        var backoff = new ExponentialBackOff(500, 2);
        backoff.setMaxInterval(5000);
        return backoff;
    }
}
