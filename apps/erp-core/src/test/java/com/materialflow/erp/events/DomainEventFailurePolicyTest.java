package com.materialflow.erp.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;

class DomainEventFailurePolicyTest {
    @Test
    void databaseBackoffSurvivesTheOldBudgetAndCapsItsInterval() {
        var failure = new ListenerExecutionFailedException("listener",
                new CannotAcquireLockException("lock timeout"));
        var execution = DomainEventFailurePolicy.backOff(failure).start();
        assertThat(execution.nextBackOff()).isEqualTo(500);
        assertThat(execution.nextBackOff()).isEqualTo(1000);
        assertThat(execution.nextBackOff()).isEqualTo(2000);
        assertThat(execution.nextBackOff()).isEqualTo(4000);
        for (int attempt = 0; attempt < 100; attempt++) {
            assertThat(execution.nextBackOff()).isEqualTo(5000);
        }
    }

    @Test
    void connectionAndTransactionAcquisitionFailuresAreRecoverableButBadSqlIsNot() {
        for (Exception failure : new Exception[] {
                new CannotGetJdbcConnectionException("offline", new SQLException("offline")),
                new CannotCreateTransactionException("pool exhausted"),
                new RecoverableDataAccessException("reconnect")}) {
            assertThat(DomainEventFailurePolicy.reason(failure)).isEqualTo("database");
            assertThat(DomainEventFailurePolicy.backOff(failure)).isNotNull();
        }
        assertThat(DomainEventFailurePolicy.backOff(new DataIntegrityViolationException("bad schema"))).isNull();
        assertThat(DomainEventFailurePolicy.backOff(new IllegalStateException("application bug"))).isNull();
        assertThat(DomainEventFailurePolicy.reason(new IllegalArgumentException("not necessarily bad input")))
                .isEqualTo("other");
    }

    @Test
    void postgresLockSqlStateIsRecognizedEvenWhenSpringLeavesItUncategorized() {
        var lock = new UncategorizedSQLException("insert", "synthetic SQL",
                new SQLException("localized message deliberately ignored", "55P03"));
        assertThat(DomainEventFailurePolicy.reason(new ListenerExecutionFailedException("wrapped", lock)))
                .isEqualTo("database");
        for (String state : new String[] {"23505", "42P01", null}) {
            var permanent = new UncategorizedSQLException("insert", "synthetic SQL",
                    new SQLException("lock timeout text is not a classifier", state));
            assertThat(DomainEventFailurePolicy.reason(permanent)).isEqualTo("other");
        }
    }

    @Test
    void malformedAndMissingIdentityAreTypedBeforeAnyDatabaseWrite() {
        var inbox = new DomainEventInboxApplicationService(null, null, new ObjectMapper(), null,
                new SimpleMeterRegistry(), java.time.Duration.ofHours(2));
        for (String value : new String[] {"not-json", "null", "[]", "", "{}", null}) {
            assertThatThrownBy(() -> inbox.consume(value)).isInstanceOf(InvalidEventEnvelopeException.class);
        }
        assertThat(DomainEventFailurePolicy.reason(new ListenerExecutionFailedException("listener",
                new InvalidEventEnvelopeException("missing event id", null)))).isEqualTo("invalid");
    }
}
