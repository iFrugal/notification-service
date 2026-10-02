package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.PushRecipient;
import com.zaxxer.hikari.HikariDataSource;
import lazydevs.persistence.connection.multitenant.TenantContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers(disabledWithoutDocker = true)
class JdbcDeadLetterStoreIT {

    private static final JdbcStoreTables TABLES = JdbcStoreTables.defaults();
    private static final Duration LONG_LEASE = Duration.ofMinutes(5);

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;

    private JdbcDeadLetterStore store;

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
        store = newStore(100);
    }

    @AfterEach
    void resetTenant() {
        TenantContext.reset();
    }

    private static JdbcDeadLetterStore newStore(int maxEntries) {
        return new JdbcDeadLetterStore(jdbc, PostgresTestSupport.JSON, TABLES, Duration.ofDays(30), maxEntries);
    }

    private static List<String> requestIds(List<DeadLetterEntry> entries) {
        return entries.stream().map(e -> e.request().getRequestId()).toList();
    }

    @Test
    void rowFromANewerVersion_withAnUnknownFailureTypeAndExtraFields_stillReads() {
        // What a 1.2 node could write: a FailureType constant 1.1.x lacks and
        // fields 1.1.x does not know, including inside a push recipient.
        store.add(PostgresTestSupport.deadLetter("acme", "req-push"));
        jdbc.sql("UPDATE " + TABLES.deadLetter() + " SET failure_type = 'SOMETHING_NEW', request = :request"
                        + " WHERE request_id = 'req-push'")
                .param("request", """
                        {"requestId":"req-push","tenantId":"acme","callerId":"billing","notificationType":"TEST",\
                        "channel":"PUSH","recipient":{"type":"PUSH","id":null,"deviceToken":"device-token-1",\
                        "title":"Hi","body":"There","data":{},"futureRecipientField":"x"},\
                        "futureRequestField":true}""")
                .update();

        DeadLetterEntry entry = store.findByRequestId("acme", "req-push").orElseThrow();

        assertThat(entry.failureType()).isEqualTo(FailureType.UNKNOWN);
        assertThat(entry.request().getRecipient()).isInstanceOfSatisfying(PushRecipient.class,
                push -> assertThat(push.deviceToken()).isEqualTo("device-token-1"));
        assertThat(store.snapshot().orElseThrow()).hasSize(1);
    }

    @Test
    void ambiguousEntry_withA12PushRecipient_roundTrips() {
        PushRecipient push = new PushRecipient(null, null, null, null, "Hi", "There", null, null, null, null,
                null, "fid-0123456789abcdef", List.of("token-1", "token-2"));
        NotificationRequest request = NotificationRequest.builder()
                .requestId("req-ambiguous").tenantId("acme").callerId("billing").notificationType("TEST")
                .channel(Channel.PUSH).recipient(push).build();
        DeadLetterEntry entry = new DeadLetterEntry(Instant.now().truncatedTo(ChronoUnit.MICROS), request,
                NotificationResponse.failure(request, "fcm", "FCM_TIMEOUT", "timed out"), 1, FailureType.AMBIGUOUS);

        store.add(entry);

        DeadLetterEntry found = store.findByRequestId("acme", "req-ambiguous").orElseThrow();
        assertThat(found.failureType()).isEqualTo(FailureType.AMBIGUOUS);
        assertThat(found.request().getRecipient()).isEqualTo(push);
        assertThat(jdbc.sql("SELECT failure_type FROM " + TABLES.deadLetter()).query(String.class).single())
                .isEqualTo("AMBIGUOUS");
    }

    @Test
    void addThenFindByRequestIdRoundTripsTheEntry() {
        DeadLetterEntry entry = PostgresTestSupport.deadLetter("acme", "req-1");

        store.add(entry);

        DeadLetterEntry found = store.findByRequestId("acme", "req-1").orElseThrow();
        assertThat(found.timestamp()).isEqualTo(entry.timestamp());
        assertThat(found.request()).isEqualTo(entry.request());
        assertThat(found.response()).isEqualTo(entry.response());
        assertThat(found.attempts()).isEqualTo(3);
        assertThat(found.failureType()).isEqualTo(entry.failureType());

        assertThat(store.findByRequestId("globex", "req-1")).isEmpty();
        assertThat(store.findByRequestId("acme", "req-2")).isEmpty();
        assertThat(store.findByRequestId("acme", null)).isEmpty();
        assertThat(jdbc.sql("SELECT provider_name || '/' || status || '/' || failure_type || '/' || attempts FROM "
                + TABLES.deadLetter()).query(String.class).single()).isEqualTo("smtp/FAILED/TRANSIENT/3");
    }

    @Test
    void duplicateAddIsIgnored() {
        store.add(PostgresTestSupport.deadLetter("acme", "req-1", 1));
        store.add(PostgresTestSupport.deadLetter("acme", "req-1", 5));
        store.add(PostgresTestSupport.deadLetter("globex", "req-1", 2));

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.findByRequestId("acme", "req-1").orElseThrow().attempts()).isEqualTo(1);
        assertThat(store.findByRequestId("globex", "req-1").orElseThrow().attempts()).isEqualTo(2);
    }

    @Test
    void addReplacesAnExpiredDuplicate() {
        store.add(PostgresTestSupport.deadLetter("acme", "req-1", 1));
        PostgresTestSupport.expire(jdbc, TABLES.deadLetter(), "request_id = 'req-1'");

        store.add(PostgresTestSupport.deadLetter("acme", "req-1", 4));

        assertThat(store.findByRequestId("acme", "req-1").orElseThrow().attempts()).isEqualTo(4);
    }

    @Test
    void snapshotIsBoundedByMaxEntriesAndMostRecentFirst() {
        JdbcDeadLetterStore bounded = newStore(5);
        for (int i = 0; i < 8; i++) {
            bounded.add(PostgresTestSupport.deadLetter("acme", "req-" + i));
        }

        assertThat(requestIds(bounded.snapshot().orElseThrow()))
                .containsExactly("req-7", "req-6", "req-5", "req-4", "req-3");
        assertThat(bounded.size()).isEqualTo(8);
    }

    @Test
    void concurrentClaimsAreDisjointAndCoverEveryRow() throws Exception {
        for (int i = 0; i < 20; i++) {
            store.add(PostgresTestSupport.deadLetter("acme", "req-" + i));
        }
        store.add(PostgresTestSupport.deadLetter("globex", "other-tenant"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<List<DeadLetterEntry>>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return store.claim("acme", 5, LONG_LEASE);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<String> all = new ArrayList<>();
            for (Future<List<DeadLetterEntry>> result : results) {
                List<DeadLetterEntry> claimed = result.get(30, TimeUnit.SECONDS);
                assertThat(claimed).hasSizeLessThanOrEqualTo(5);
                all.addAll(requestIds(claimed));
            }
            Set<String> distinct = new HashSet<>(all);
            assertThat(all).hasSize(20);
            assertThat(distinct).hasSize(20).doesNotContain("other-tenant");
        } finally {
            pool.shutdownNow();
        }
        assertThat(store.claim("acme", 5, LONG_LEASE)).isEmpty();
    }

    @Test
    void claimReturnsOldestFirstAndHonoursTheLimit() {
        for (int i = 0; i < 4; i++) {
            store.add(PostgresTestSupport.deadLetter("acme", "req-" + i));
        }

        assertThat(requestIds(store.claim("acme", 3, LONG_LEASE))).containsExactly("req-0", "req-1", "req-2");
        assertThat(requestIds(store.claim("acme", 3, LONG_LEASE))).containsExactly("req-3");
        assertThat(store.claim("acme", 0, LONG_LEASE)).isEmpty();
        assertThatThrownBy(() -> store.claim("acme", 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimedRowIsNotReclaimedUntilTheLeaseExpires() {
        store.add(PostgresTestSupport.deadLetter("acme", "req-1"));
        store.add(PostgresTestSupport.deadLetter("acme", "req-2"));

        assertThat(store.claim("acme", 10, Duration.ofMillis(800))).hasSize(2);
        assertThat(store.claim("acme", 10, LONG_LEASE)).isEmpty();

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> !store.claim("acme", 10, LONG_LEASE).isEmpty());
        // Claimed again by the successful poll above, with the long lease.
        assertThat(store.claim("acme", 10, LONG_LEASE)).isEmpty();
        // Claiming never hides rows from the read paths.
        assertThat(store.snapshot().orElseThrow()).hasSize(2);
        assertThat(store.findByRequestId("acme", "req-1")).isPresent();
    }

    @Test
    void targetedClaimLeasesOnlyThatRowAndExcludesItFromEveryClaim() {
        store.add(PostgresTestSupport.deadLetter("acme", "req-1"));
        store.add(PostgresTestSupport.deadLetter("acme", "req-2"));
        store.add(PostgresTestSupport.deadLetter("globex", "req-1"));

        DeadLetterEntry claimed = store.claim("acme", "req-1", LONG_LEASE).orElseThrow();
        assertThat(claimed.request().getRequestId()).isEqualTo("req-1");
        assertThat(claimed.request().getTenantId()).isEqualTo("acme");

        assertThat(store.claim("acme", "req-1", LONG_LEASE)).isEmpty();
        assertThat(requestIds(store.claim("acme", 10, LONG_LEASE))).containsExactly("req-2");
        assertThat(store.claim("globex", "req-1", LONG_LEASE)).isPresent();
        assertThat(store.claim("acme", "missing", LONG_LEASE)).isEmpty();
        assertThat(store.claim("acme", null, LONG_LEASE)).isEmpty();
        assertThatThrownBy(() -> store.claim("acme", "req-2", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        // Claiming never hides the row from the read path.
        assertThat(store.findByRequestId("acme", "req-1")).isPresent();
    }

    @Test
    void targetedClaimAfterReleaseOrLeaseExpirySucceedsAgain() {
        store.add(PostgresTestSupport.deadLetter("acme", "req-1"));
        store.add(PostgresTestSupport.deadLetter("acme", "req-2"));
        assertThat(store.claim("acme", "req-1", LONG_LEASE)).isPresent();
        assertThat(store.claim("acme", 10, Duration.ofMillis(800))).hasSize(1);

        store.release("acme", "req-1");
        assertThat(store.claim("acme", "req-1", LONG_LEASE)).isPresent();

        assertThat(store.claim("acme", "req-2", LONG_LEASE)).isEmpty();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> store.claim("acme", "req-2", LONG_LEASE).isPresent());
    }

    @Test
    void concurrentTargetedClaimsOfOneRowHaveExactlyOneWinner() throws Exception {
        store.add(PostgresTestSupport.deadLetter("acme", "req-1"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return store.claim("acme", "req-1", LONG_LEASE).isPresent();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int winners = 0;
            for (Future<Boolean> result : results) {
                if (Boolean.TRUE.equals(result.get(30, TimeUnit.SECONDS))) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void releaseMakesARowClaimableAgain() {
        store.add(PostgresTestSupport.deadLetter("acme", "req-1"));
        store.add(PostgresTestSupport.deadLetter("acme", "req-2"));
        assertThat(store.claim("acme", 10, LONG_LEASE)).hasSize(2);

        store.release("acme", "req-2");
        store.release("acme", "missing");
        store.release("globex", "req-1");

        assertThat(requestIds(store.claim("acme", 10, LONG_LEASE))).containsExactly("req-2");
    }

    @Test
    void removeAcknowledgesAClaimedRow() {
        store.add(PostgresTestSupport.deadLetter("acme", "req-1"));
        store.add(PostgresTestSupport.deadLetter("acme", "req-2"));
        assertThat(store.claim("acme", 1, LONG_LEASE)).hasSize(1);

        assertThat(store.remove("globex", "req-1")).isFalse();
        assertThat(store.remove("acme", "req-1")).isTrue();
        assertThat(store.remove("acme", "req-1")).isFalse();

        assertThat(store.findByRequestId("acme", "req-1")).isEmpty();
        assertThat(requestIds(store.claim("acme", 10, LONG_LEASE))).containsExactly("req-2");
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void tenantColumnComesFromTheRequestThenTenantContextElseNull() {
        TenantContext.setTenantId("ctx-tenant");
        store.add(PostgresTestSupport.deadLetter(null, "from-context"));
        store.add(PostgresTestSupport.deadLetter("own-tenant", "from-request"));
        TenantContext.reset();
        store.add(PostgresTestSupport.deadLetter(null, "no-tenant"));

        assertThat(tenantOf("from-context")).isEqualTo("ctx-tenant");
        assertThat(tenantOf("from-request")).isEqualTo("own-tenant");
        assertThat(tenantOf("no-tenant")).isNull();

        assertThat(store.findByRequestId("ctx-tenant", "from-context")).isPresent();
        assertThat(store.findByRequestId(null, "no-tenant")).isPresent();
        assertThat(requestIds(store.claim(null, 10, LONG_LEASE))).containsExactly("no-tenant");
    }

    @Test
    void sizeIsAGlobalCountRegardlessOfTenantContext() {
        store.add(PostgresTestSupport.deadLetter("acme", "a-1"));
        store.add(PostgresTestSupport.deadLetter("acme", "a-2"));
        store.add(PostgresTestSupport.deadLetter("globex", "g-1"));

        assertThat(store.size()).isEqualTo(3);
        TenantContext.setTenantId("acme");
        assertThat(store.size()).isEqualTo(3);
        TenantContext.setTenantId("globex");
        assertThat(store.size()).isEqualTo(3);
    }

    @Test
    void expiredRowsAreHiddenAndPurgedInBatches() {
        for (int i = 0; i < 5; i++) {
            store.add(PostgresTestSupport.deadLetter("acme", "req-" + i));
        }
        PostgresTestSupport.expire(jdbc, TABLES.deadLetter(), "request_id IN ('req-0', 'req-1', 'req-2')");

        assertThat(store.size()).isEqualTo(2);
        assertThat(requestIds(store.snapshot().orElseThrow())).containsExactly("req-4", "req-3");
        assertThat(store.findByRequestId("acme", "req-0")).isEmpty();
        assertThat(requestIds(store.claim("acme", 10, LONG_LEASE))).containsExactly("req-3", "req-4");

        assertThat(store.purgeExpired(2)).isEqualTo(2);
        assertThat(store.purgeExpired(2)).isEqualTo(1);
        assertThat(store.purgeExpired(2)).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM " + TABLES.deadLetter()).query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void addNeverThrowsWhenTheDatabaseFails() {
        JdbcDeadLetterStore broken = new JdbcDeadLetterStore(jdbc, PostgresTestSupport.JSON,
                new JdbcStoreTables(null, "missing_"), Duration.ofDays(1), 10);

        broken.add(PostgresTestSupport.deadLetter("acme", "req-1"));
        broken.release("acme", "req-1");
        assertThat(broken.remove("acme", "req-1")).isFalse();
    }

    private static String tenantOf(String requestId) {
        List<String> tenants = jdbc.sql("SELECT tenant_id FROM " + TABLES.deadLetter() + " WHERE request_id = :id")
                .param("id", requestId)
                .query((rs, n) -> rs.getString(1))
                .list();
        assertThat(tenants).hasSize(1);
        return tenants.get(0);
    }
}
