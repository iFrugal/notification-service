package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers(disabledWithoutDocker = true)
class JdbcIdempotencyStoreIT {

    private static final JdbcStoreTables TABLES = JdbcStoreTables.defaults();

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;

    private JdbcIdempotencyStore store;

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
    void setUp() {
        PostgresTestSupport.truncate(jdbc, TABLES);
        store = newStore(Duration.ofHours(24));
    }

    private static JdbcIdempotencyStore newStore(Duration ttl) {
        return new JdbcIdempotencyStore(jdbc, PostgresTestSupport.JSON, TABLES, ttl, 2);
    }

    private static NotificationResponse sentResponse(String tenant, String requestId) {
        return NotificationResponse.success(PostgresTestSupport.request(tenant, requestId), "smtp", "msg-" + requestId);
    }

    @Test
    void duplicateMarkInProgressReturnsFalse() {
        IdempotencyKey key = new IdempotencyKey("acme", "billing", "k-1");

        assertThat(store.markInProgress(key, "req-1")).isTrue();
        assertThat(store.markInProgress(key, "req-2")).isFalse();

        IdempotencyRecord rec = store.findExisting(key).orElseThrow();
        assertThat(rec.notificationId()).isEqualTo("req-1");
        assertThat(rec.status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(rec.response()).isNull();
        assertThat(rec.recordedAt()).isNotNull();
    }

    @Test
    void duplicateOfCompletedKeyReturnsFalse() {
        IdempotencyKey key = new IdempotencyKey("acme", "billing", "k-1");
        store.markInProgress(key, "req-1");
        store.markComplete(key, sentResponse("acme", "req-1"));

        assertThat(store.markInProgress(key, "req-2")).isFalse();
    }

    @Test
    void markInProgressSucceedsAgainOnceTheTtlElapses() {
        JdbcIdempotencyStore shortLived = newStore(Duration.ofMillis(500));
        IdempotencyKey key = new IdempotencyKey("acme", "billing", "k-ttl");

        assertThat(shortLived.markInProgress(key, "req-1")).isTrue();
        assertThat(shortLived.markInProgress(key, "req-2")).isFalse();

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> shortLived.findExisting(key).isEmpty());
        assertThat(shortLived.markInProgress(key, "req-3")).isTrue();
        assertThat(shortLived.findExisting(key)).get()
                .extracting(IdempotencyRecord::notificationId, IdempotencyRecord::status)
                .containsExactly("req-3", IdempotencyStatus.IN_PROGRESS);
    }

    @Test
    void racingCallersGetExactlyOneWinner() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 5; round++) {
                IdempotencyKey key = new IdempotencyKey("acme", "billing", "race-" + round);
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    String notificationId = "req-" + round + "-" + i;
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return store.markInProgress(key, notificationId);
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();

                int winners = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(30, TimeUnit.SECONDS)) {
                        winners++;
                    }
                }
                assertThat(winners).as("winners in round %d", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void findExistingHidesExpiredRows() {
        IdempotencyKey key = new IdempotencyKey("acme", "billing", "k-1");
        store.markInProgress(key, "req-1");
        assertThat(store.findExisting(key)).isPresent();

        PostgresTestSupport.expire(jdbc, TABLES.idempotency(), "idem_key = 'k-1'");

        assertThat(store.findExisting(key)).isEmpty();
        // The expired row is still physically there until purged, and a
        // new claim takes it over.
        assertThat(store.markInProgress(key, "req-2")).isTrue();
        assertThat(store.findExisting(key).orElseThrow().notificationId()).isEqualTo("req-2");
    }

    @Test
    void sameKeyInTwoTenantsDoesNotCollide() {
        IdempotencyKey acme = new IdempotencyKey("acme", "billing", "shared");
        IdempotencyKey globex = new IdempotencyKey("globex", "billing", "shared");

        assertThat(store.markInProgress(acme, "acme-req")).isTrue();
        assertThat(store.markInProgress(globex, "globex-req")).isTrue();

        assertThat(store.findExisting(acme).orElseThrow().notificationId()).isEqualTo("acme-req");
        assertThat(store.findExisting(globex).orElseThrow().notificationId()).isEqualTo("globex-req");
        assertThat(jdbc.sql("SELECT tenant_id FROM " + TABLES.idempotency() + " ORDER BY tenant_id")
                .query(String.class).list()).containsExactly("acme", "globex");
    }

    @Test
    void callerIsPartOfTheScopeAndNullCallerIsOneScope() {
        IdempotencyKey billing = new IdempotencyKey("acme", "billing", "shared");
        IdempotencyKey shipping = new IdempotencyKey("acme", "shipping", "shared");
        IdempotencyKey anonymous = new IdempotencyKey("acme", null, "shared");

        assertThat(store.markInProgress(billing, "r1")).isTrue();
        assertThat(store.markInProgress(shipping, "r2")).isTrue();
        assertThat(store.markInProgress(anonymous, "r3")).isTrue();
        assertThat(store.markInProgress(new IdempotencyKey("acme", null, "shared"), "r4")).isFalse();
        assertThat(store.findExisting(anonymous).orElseThrow().notificationId()).isEqualTo("r3");
    }

    @Test
    void markCompleteStoresTheResponseAndKeepsTheOriginalNotificationId() {
        IdempotencyKey key = new IdempotencyKey("acme", "billing", "k-1");
        store.markInProgress(key, "req-1");
        NotificationResponse response = sentResponse("acme", "req-1-retry");

        store.markComplete(key, response);

        IdempotencyRecord rec = store.findExisting(key).orElseThrow();
        assertThat(rec.status()).isEqualTo(IdempotencyStatus.COMPLETE);
        assertThat(rec.notificationId()).isEqualTo("req-1");
        assertThat(rec.response()).isEqualTo(response);
        assertThat(rec.response().providerMessageId()).isEqualTo("msg-req-1-retry");
    }

    @Test
    void markCompleteWithoutAPriorRowStillCachesTheResponse() {
        IdempotencyKey key = new IdempotencyKey("acme", "billing", "k-late");

        store.markComplete(key, sentResponse("acme", "req-9"));

        IdempotencyRecord rec = store.findExisting(key).orElseThrow();
        assertThat(rec.status()).isEqualTo(IdempotencyStatus.COMPLETE);
        assertThat(rec.notificationId()).isEqualTo("req-9");
    }

    @Test
    void purgeExpiredDeletesOnlyExpiredRowsInBatches() {
        for (int i = 0; i < 5; i++) {
            store.markInProgress(new IdempotencyKey("acme", "billing", "k-" + i), "req-" + i);
        }
        PostgresTestSupport.expire(jdbc, TABLES.idempotency(), "idem_key IN ('k-0', 'k-1', 'k-2')");

        assertThat(store.purgeExpired(2)).isEqualTo(2);
        assertThat(store.purgeExpired(2)).isEqualTo(1);
        assertThat(store.purgeExpired(2)).isZero();

        assertThat(jdbc.sql("SELECT idem_key FROM " + TABLES.idempotency() + " ORDER BY idem_key")
                .query(String.class).list()).containsExactly("k-3", "k-4");
    }

    @Test
    void evictExpiredLoopsUntilEveryExpiredRowIsGone() {
        for (int i = 0; i < 7; i++) {
            store.markInProgress(new IdempotencyKey("acme", "billing", "k-" + i), "req-" + i);
        }
        PostgresTestSupport.expire(jdbc, TABLES.idempotency(), "idem_key <> 'k-6'");

        store.evictExpired(); // batch size 2, so four statements

        assertThat(jdbc.sql("SELECT idem_key FROM " + TABLES.idempotency())
                .query(String.class).list()).containsExactly("k-6");
    }
}
