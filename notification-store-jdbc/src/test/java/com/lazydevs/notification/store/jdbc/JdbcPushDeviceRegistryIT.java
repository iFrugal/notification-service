package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.push.PushDevice;
import com.lazydevs.notification.api.push.PushDeviceRegistration;
import com.lazydevs.notification.api.push.PushDeviceRegistry;
import com.lazydevs.notification.api.push.PushPlatform;
import com.lazydevs.notification.core.push.PushDeviceRegistryContractTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry contract over {@link JdbcPushDeviceRegistry} on PostgreSQL, plus what only a
 * shared database shows: concurrent registrations, the batch purge and the stored columns.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcPushDeviceRegistryIT extends PushDeviceRegistryContractTest {

    private static final JdbcStoreTables TABLES = JdbcStoreTables.defaults();
    private static final Duration PURGE_AFTER = Duration.ofDays(270);

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;

    @BeforeAll
    static void startDatabase() {
        postgres = PostgresTestSupport.startPostgres();
        dataSource = PostgresTestSupport.dataSource(postgres);
        jdbc = JdbcClient.create(dataSource);
        PostgresTestSupport.createTables(jdbc, TABLES);
    }

    @AfterAll
    static void stopDatabase() {
        dataSource.close();
        postgres.stop();
    }

    @BeforeEach
    void truncate() {
        PostgresTestSupport.truncate(jdbc, TABLES);
    }

    @Override
    protected PushDeviceRegistry newRegistry() {
        return registry(MAX_DEVICES);
    }

    private static JdbcPushDeviceRegistry registry(int maxDevices) {
        return new JdbcPushDeviceRegistry(dataSource, PostgresTestSupport.JSON, TABLES, maxDevices, STALE_AFTER,
                PURGE_AFTER);
    }

    @Override
    protected void ageDevices(Duration by) {
        jdbc.sql("UPDATE " + TABLES.pushDevice() + " SET created_at = created_at - :by * INTERVAL '1 millisecond',"
                        + " last_seen_at = last_seen_at - :by * INTERVAL '1 millisecond',"
                        + " expires_at = expires_at - :by * INTERVAL '1 millisecond'")
                .param("by", by.toMillis())
                .update();
    }

    @Override
    protected Instant now() {
        return jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
    }

    @Test
    void theTokenIsStoredWithItsHashes_andTheAttributesAsJson() {
        registry.register(new PushDeviceRegistration(null, "u1", "inst-1", null, PushPlatform.ANDROID, "token-1",
                null, null, Map.of("model", "Pixel")));

        Map<String, Object> row = jdbc.sql("SELECT tenant_key, token_hash, token_sha256, attributes, expires_at > now()"
                + " + INTERVAL '269 days' AS expires_late FROM " + TABLES.pushDevice()).query().singleRow();
        assertThat(row).containsEntry("tenant_key", "")
                .containsEntry("token_hash", com.lazydevs.notification.api.push.PushTokens.hash("token-1"))
                .containsEntry("token_sha256", com.lazydevs.notification.api.push.PushTokens.sha256Hex("token-1"))
                .containsEntry("attributes", "{\"model\":\"Pixel\"}")
                .containsEntry("expires_late", true);
    }

    @Test
    void concurrentRegistrationsOfOneUser_neverKeepMoreThanTheMaximum() throws Exception {
        List<Callable<PushDevice>> registrations = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String n = String.valueOf(i);
            registrations.add(() -> registry.register(
                    PushDeviceRegistration.of("u1", "inst-" + n, PushPlatform.ANDROID, "token-" + n)));
        }

        runAll(registrations);

        assertThat(registry.activeDevices("u1", null)).hasSize(MAX_DEVICES);
    }

    @Test
    void concurrentRegistrationsOfOneTokenByManyUsers_leaveOneOwner() throws Exception {
        List<Callable<PushDevice>> registrations = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String n = String.valueOf(i);
            registrations.add(() -> registry.register(
                    PushDeviceRegistration.of("user-" + n, "inst-" + n, PushPlatform.ANDROID, "shared-token")));
        }

        runAll(registrations);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM " + TABLES.pushDevice()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void thePurger_deletesExpiredDevices_inBatches() {
        for (int i = 0; i < 5; i++) {
            registry.register(PushDeviceRegistration.of("user-" + i, "inst", PushPlatform.IOS, "token-" + i));
        }
        PostgresTestSupport.expire(jdbc, TABLES.pushDevice(), "user_id <> 'user-4'");

        int purged = new JdbcStorePurger(List.of(registry(MAX_DEVICES)), 2).purgeAll();

        assertThat(purged).isEqualTo(4);
        assertThat(registry.activeDevices("user-4", null)).hasSize(1);
    }

    private static void runAll(List<Callable<PushDevice>> tasks) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            for (Future<PushDevice> future : pool.invokeAll(tasks)) {
                future.get();
            }
        }
    }
}
