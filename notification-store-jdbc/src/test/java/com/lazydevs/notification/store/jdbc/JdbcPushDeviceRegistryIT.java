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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    @Test
    void concurrentTokenSwapsBetweenTwoDevices_endWithEachTokenOwnedOnce() throws Exception {
        // A/iA/X + B/iB/Y -> A/iA/Y + B/iB/X: the two registrations take different advisory locks
        // and delete each other's rows, which PostgreSQL may abort as a deadlock; the registry
        // re-runs the aborted transaction.
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 50; round++) {
                PostgresTestSupport.truncate(jdbc, TABLES);
                registry.register(PushDeviceRegistration.of("A", "iA", PushPlatform.ANDROID, "X"));
                registry.register(PushDeviceRegistration.of("B", "iB", PushPlatform.ANDROID, "Y"));
                CountDownLatch go = new CountDownLatch(1);
                Future<PushDevice> first = pool.submit(() -> {
                    go.await();
                    return registry.register(PushDeviceRegistration.of("A", "iA", PushPlatform.ANDROID, "Y"));
                });
                Future<PushDevice> second = pool.submit(() -> {
                    go.await();
                    return registry.register(PushDeviceRegistration.of("B", "iB", PushPlatform.ANDROID, "X"));
                });
                go.countDown();
                assertThat(first.get().token()).isEqualTo("Y");
                assertThat(second.get().token()).isEqualTo("X");

                List<String> rows = jdbc.sql("SELECT user_id || '/' || installation_id || '/' || token FROM "
                        + TABLES.pushDevice() + " ORDER BY user_id").query(String.class).list();
                assertThat(rows).as("round %d", round).containsExactly("A/iA/Y", "B/iB/X");
            }
        }
    }

    @Test
    void aRegistrationThatWaitedForALock_isStampedWithTheTimeItGotTheLock() throws Exception {
        String userLock = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('push-user/' || '' || '/' || 'u-wait', 0))";
        OffsetDateTime released;
        Future<PushDevice> waiting;
        try (ExecutorService pool = Executors.newSingleThreadExecutor();
             Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                statement.execute(userLock);
                waiting = pool.submit(() -> registry.register(
                        PushDeviceRegistration.of("u-wait", "inst", PushPlatform.IOS, "token-wait")));
                awaitAdvisoryLockWaiter();
                // Let the waiting transaction age, so its now() is well before the release.
                Thread.sleep(300);
                try (ResultSet rs = statement.executeQuery("SELECT clock_timestamp()")) {
                    rs.next();
                    released = rs.getObject(1, OffsetDateTime.class);
                }
            }
            blocker.commit();
            PushDevice device = waiting.get(30, TimeUnit.SECONDS);

            assertThat(device.createdAt()).isAfterOrEqualTo(released.toInstant());
            assertThat(device.lastSeenAt()).isEqualTo(device.createdAt());
            Map<String, Object> row = jdbc.sql("SELECT expires_at = last_seen_at + (:purge * INTERVAL '1 millisecond')"
                            + " AS one_reading FROM " + TABLES.pushDevice() + " WHERE user_id = 'u-wait'")
                    .param("purge", PURGE_AFTER.toMillis()).query().singleRow();
            assertThat(row).containsEntry("one_reading", true);
        }
    }

    /** Waits until some session waits for an advisory lock. */
    private static void awaitAdvisoryLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (jdbc.sql("SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")
                .query(Long.class).single() == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the registration never waited for the advisory lock");
            }
            Thread.sleep(20);
        }
    }

    private static void runAll(List<Callable<PushDevice>> tasks) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            for (Future<PushDevice> future : pool.invokeAll(tasks)) {
                future.get();
            }
        }
    }
}
