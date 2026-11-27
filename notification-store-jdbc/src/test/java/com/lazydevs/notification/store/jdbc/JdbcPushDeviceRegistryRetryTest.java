package com.lazydevs.notification.store.jdbc;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.UncategorizedSQLException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which failures make {@link JdbcPushDeviceRegistry#register} run its transaction again;
 * {@code JdbcPushDeviceRegistryIT} drives the deadlock itself on PostgreSQL.
 */
class JdbcPushDeviceRegistryRetryTest {

    @Test
    void uniqueKeyCollisionsDeadlocksAndSerializationFailures_areRetried() {
        assertThat(JdbcPushDeviceRegistry.isRetryable(new DuplicateKeyException("dup"))).isTrue();
        assertThat(JdbcPushDeviceRegistry.isRetryable(new PessimisticLockingFailureException("deadlock"))).isTrue();
        assertThat(JdbcPushDeviceRegistry.isRetryable(new CannotAcquireLockException("lock"))).isTrue();
        assertThat(JdbcPushDeviceRegistry.isRetryable(uncategorized("40P01"))).isTrue();
        assertThat(JdbcPushDeviceRegistry.isRetryable(uncategorized("40001"))).isTrue();
    }

    @Test
    void otherFailures_areNotRetried() {
        assertThat(JdbcPushDeviceRegistry.isRetryable(uncategorized("23503"))).isFalse();
        assertThat(JdbcPushDeviceRegistry.isRetryable(uncategorized(null))).isFalse();
        assertThat(JdbcPushDeviceRegistry.isRetryable(new DataIntegrityViolationException("fk"))).isFalse();
    }

    private static UncategorizedSQLException uncategorized(String sqlState) {
        return new UncategorizedSQLException("register", "INSERT ...",
                new SQLException("wrapped", sqlState, new SQLException("inner")));
    }
}
