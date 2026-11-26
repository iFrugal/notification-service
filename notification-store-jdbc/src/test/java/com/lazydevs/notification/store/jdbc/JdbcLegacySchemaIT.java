package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.audit.NotificationAuditStore;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DD-32 backward compatibility: a 1.2.0 schema (no {@code fingerprint}
 * column, no audit table) keeps working after the jar is upgraded, in a
 * non-default schema and with a non-default table prefix.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcLegacySchemaIT {

    private static final JdbcStoreTables LEGACY = new JdbcStoreTables("legacy", "old_");
    private static final JdbcStoreTables CURRENT = new JdbcStoreTables("legacy", "new_");
    private static final String ALTER =
            "ALTER TABLE legacy.old_idempotency ADD COLUMN IF NOT EXISTS fingerprint VARCHAR(64)";

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;

    @BeforeAll
    static void startDatabase() {
        postgres = PostgresTestSupport.startPostgres();
        dataSource = PostgresTestSupport.dataSource(postgres);
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("CREATE SCHEMA legacy").update();
        PostgresTestSupport.createTables(jdbc, CURRENT);
    }

    @AfterAll
    static void stopDatabase() {
        dataSource.close();
        postgres.stop();
    }

    @BeforeEach
    void legacyTables() {
        jdbc.sql("DROP TABLE IF EXISTS legacy.old_idempotency, legacy.old_dead_letter, legacy.old_delivery_event,"
                + " legacy.old_audit").update();
        PostgresTestSupport.createTables(jdbc, LEGACY);
        // What 1.2.0 created: no fingerprint column and no audit table.
        jdbc.sql("ALTER TABLE legacy.old_idempotency DROP COLUMN fingerprint").update();
        jdbc.sql("DROP TABLE legacy.old_audit").update();
    }

    @Test
    void missingColumn_isDetected_andTheStoreRunsWithoutFingerprints() {
        JdbcIdempotencyStore store = store(LEGACY, false);

        assertThat(store.detectFingerprintColumn()).contains(false);
        assertFullCycleWithoutFingerprint(store, "k-detected");
    }

    @Test
    void the120Constructor_detectsOnFirstUse() {
        JdbcIdempotencyStore store = new JdbcIdempotencyStore(jdbc, PostgresTestSupport.JSON, LEGACY,
                Duration.ofHours(1), 10);

        assertFullCycleWithoutFingerprint(store, "k-lazy");
    }

    @Test
    void missingColumn_withRequireFingerprint_failsWithTheAlterStatement() {
        JdbcIdempotencyStore store = store(LEGACY, true);

        assertThatThrownBy(store::detectFingerprintColumn)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reject-key-reuse")
                .hasMessageContaining(ALTER);
    }

    @Test
    void afterTheAlter_fingerprintsAreStored() {
        jdbc.sql(ALTER).update();
        JdbcIdempotencyStore store = store(LEGACY, true);

        assertThat(store.detectFingerprintColumn()).contains(true);
        IdempotencyKey key = new IdempotencyKey("acme", "billing", "k-altered");
        assertThat(store.markInProgress(key, "r-1", "fp-1")).isTrue();
        assertThat(store.findExisting(key)).map(IdempotencyRecord::fingerprint).contains("fp-1");
    }

    @Test
    void missingTable_cannotBeDecided_andIsProbedAgain() {
        JdbcIdempotencyStore store = store(new JdbcStoreTables("legacy", "absent_"), true);

        assertThat(store.detectFingerprintColumn()).isEmpty();
    }

    @Test
    void autoConfiguration_startsOnTheLegacySchema_withoutTheAuditTable() {
        runner().withPropertyValues("notification.dead-letter.enabled=true",
                        "notification.delivery-events.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // The audit table is only needed with notification.audit.enabled=true.
                    assertThat(context).doesNotHaveBean(NotificationAuditStore.class);
                    IdempotencyStore store = context.getBean(IdempotencyStore.class);
                    assertFullCycleWithoutFingerprint((JdbcIdempotencyStore) store, "k-context");
                    assertThat(context.getBean(JdbcStorePurger.class).purgeAll()).isZero();
                });
    }

    @Test
    void autoConfiguration_withRejectKeyReuse_failsStartupWithTheAlterStatement() {
        runner().withPropertyValues("notification.idempotency.reject-key-reuse=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining(ALTER);
                });
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JdbcStoreAutoConfiguration.class))
                // Not closeable, so the context does not close the shared pool.
                .withBean(DataSource.class, () -> new DelegatingDataSource(dataSource))
                .withPropertyValues("notification.store.type=jdbc",
                        "notification.store.jdbc.schema=legacy",
                        "notification.store.jdbc.table-prefix=old_");
    }

    private static JdbcIdempotencyStore store(JdbcStoreTables tables, boolean requireFingerprint) {
        return new JdbcIdempotencyStore(jdbc, PostgresTestSupport.JSON, tables, Duration.ofHours(1), 10,
                requireFingerprint);
    }

    private static void assertFullCycleWithoutFingerprint(JdbcIdempotencyStore store, String idemKey) {
        IdempotencyKey key = new IdempotencyKey("acme", "billing", idemKey);
        assertThat(store.markInProgress(key, "r-1", "fp-ignored")).isTrue();
        assertThat(store.markInProgress(key, "r-2", "fp-ignored")).isFalse();
        IdempotencyRecord inProgress = store.findExisting(key).orElseThrow();
        assertThat(inProgress.status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(inProgress.fingerprint()).isNull();

        NotificationResponse failed = NotificationResponse.failure(
                PostgresTestSupport.request("acme", "r-1"), "smtp", "E", "m");
        assertThat(store.settleInProgress(key, "r-1", failed)).isTrue();
        IdempotencyRecord complete = store.findExisting(key).orElseThrow();
        assertThat(complete.status()).isEqualTo(IdempotencyStatus.COMPLETE);
        assertThat(complete.response().status()).isEqualTo(NotificationStatus.FAILED);

        store.markComplete(key, failed);
        assertThat(store.release(key, "r-1")).isTrue();
        assertThat(store.findExisting(key)).isEmpty();
    }
}
