package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.service.DefaultNotificationService;
import com.lazydevs.notification.core.service.NotificationAuditService;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private static NotificationResponse failedResponse(String tenant, String requestId) {
        return NotificationResponse.failure(PostgresTestSupport.request(tenant, requestId), "smtp",
                "SMTP_421", "try again later");
    }

    private static void insertCompleteRow(String idemKey, String notificationId, String responseJson) {
        jdbc.sql("INSERT INTO " + TABLES.idempotency()
                        + " (tenant_id, caller_id, idem_key, notification_id, status, response,"
                        + " created_at, recorded_at, expires_at)"
                        + " VALUES ('acme', 'billing', :key, :id, 'COMPLETE', :response,"
                        + " now(), now(), now() + INTERVAL '1 hour')")
                .param("key", idemKey)
                .param("id", notificationId)
                .param("response", responseJson)
                .update();
    }

    private static String rawResponse(String idemKey) {
        return jdbc.sql("SELECT response FROM " + TABLES.idempotency() + " WHERE idem_key = :key")
                .param("key", idemKey)
                .query(String.class)
                .single();
    }

    private static NotificationRequest keyedRequest(String requestId, String idempotencyKey) {
        NotificationRequest request = PostgresTestSupport.request("acme", requestId);
        request.setIdempotencyKey(idempotencyKey);
        return request;
    }

    /** The real send path over this store, with the provider and template engine stubbed. */
    private DefaultNotificationService serviceOver(NotificationProvider provider) {
        ProviderRegistry registry = mock(ProviderRegistry.class);
        when(registry.getProvider(anyString(), any(), any())).thenReturn(provider);
        NotificationTemplateEngine templates = mock(NotificationTemplateEngine.class);
        when(templates.render(any())).thenReturn(new RenderedContent("subj", "body", null, "ORDER_CONFIRMATION"));
        return new DefaultNotificationService(new NotificationProperties(), registry, templates,
                mock(NotificationAuditService.class), Optional.of(store), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
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
    void releaseAfterAFailedOutcomeLetsARetryClaimTheKey() {
        IdempotencyKey key = new IdempotencyKey("acme", "billing", "k-failed");
        store.markInProgress(key, "req-1");
        store.markComplete(key, failedResponse("acme", "req-1"));

        assertThat(store.release(key, "req-1")).isTrue();

        assertThat(store.findExisting(key)).isEmpty();
        assertThat(store.markInProgress(key, "req-2")).isTrue();
        assertThat(store.findExisting(key).orElseThrow().notificationId()).isEqualTo("req-2");
    }

    @Test
    void releaseWithAnotherNotificationIdReturnsFalseAndKeepsTheRow() {
        IdempotencyKey key = new IdempotencyKey("acme", "billing", "k-failed");
        store.markInProgress(key, "req-1");
        store.markComplete(key, failedResponse("acme", "req-1"));

        assertThat(store.release(key, "req-other")).isFalse();
        assertThat(store.release(new IdempotencyKey("acme", "shipping", "k-failed"), "req-1")).isFalse();

        assertThat(store.findExisting(key).orElseThrow().notificationId()).isEqualTo("req-1");
        assertThat(store.markInProgress(key, "req-2")).isFalse();
    }

    @Test
    void releaseNeverRemovesAnInProgressRow() {
        IdempotencyKey key = new IdempotencyKey("acme", "billing", "k-in-flight");
        store.markInProgress(key, "req-1");

        assertThat(store.release(key, "req-1")).isFalse();

        assertThat(store.findExisting(key).orElseThrow().status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    @Test
    void concurrentRetriesOnAFailedKeyGetExactlyOneWinner() throws Exception {
        // Mirrors the service's retry path: every racer releases the failed
        // row, then tries markInProgress. markInProgress stays the gate.
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 5; round++) {
                IdempotencyKey key = new IdempotencyKey("acme", "billing", "retry-race-" + round);
                store.markInProgress(key, "failed-req");
                store.markComplete(key, failedResponse("acme", "failed-req"));
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    String notificationId = "retry-" + round + "-" + i;
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        store.release(key, "failed-req");
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
    void serviceRetriesAFailedSendUnderTheSameKeyAndThenReplaysTheSuccess() {
        NotificationProvider provider = mock(NotificationProvider.class);
        when(provider.getProviderName()).thenReturn("smtp");
        when(provider.send(any(), any()))
                .thenReturn(SendResult.failure("SMTP_421", "try again later", FailureType.TRANSIENT))
                .thenReturn(SendResult.success("msg-2"));
        DefaultNotificationService service = serviceOver(provider);

        NotificationResponse first = service.send(keyedRequest("req-1", "order-77"));
        NotificationResponse retry = service.send(keyedRequest("req-2", "order-77"));
        NotificationResponse duplicate = service.send(keyedRequest("req-3", "order-77"));

        assertThat(first.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(retry.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(duplicate.idempotentReplay()).isTrue();
        assertThat(duplicate.requestId()).isEqualTo("req-2");
        verify(provider, times(2)).send(any(), any());
    }

    @Test
    void serviceReleasesAFailedRowLeftByAnOlderVersion() {
        IdempotencyKey key = new IdempotencyKey("acme", "billing", "order-legacy");
        store.markInProgress(key, "req-old");
        store.markComplete(key, failedResponse("acme", "req-old"));
        NotificationProvider provider = mock(NotificationProvider.class);
        when(provider.getProviderName()).thenReturn("smtp");
        when(provider.send(any(), any())).thenReturn(SendResult.success("msg-new"));

        NotificationResponse retry = serviceOver(provider).send(keyedRequest("req-new", "order-legacy"));

        assertThat(retry.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(store.findExisting(key).orElseThrow())
                .extracting(IdempotencyRecord::notificationId, r -> r.response().status())
                .containsExactly("req-new", NotificationStatus.SENT);
    }

    /** A FAILED response exactly as 1.1.0 stored it, provider text included. */
    private static final String FAILED_RESPONSE_1_1_0 = """
            {"requestId":"req-old","correlationId":null,"tenantId":"acme","callerId":"billing",\
            "channel":"EMAIL","provider":"smtp","status":"FAILED","providerMessageId":null,\
            "errorCode":"SMTP_550","errorMessage":"mailbox john.doe@example.com unavailable",\
            "receivedAt":"2026-10-01T10:00:00.123456Z","processedAt":"2026-10-01T10:00:00.223456Z",\
            "sentAt":null}""";

    @Test
    void rowWrittenBy110_stillReads_andTheServiceRetriesIt() {
        insertCompleteRow("order-1-1-0", "req-old", FAILED_RESPONSE_1_1_0);
        IdempotencyKey key = new IdempotencyKey("acme", "billing", "order-1-1-0");

        IdempotencyRecord legacy = store.findExisting(key).orElseThrow();
        assertThat(legacy.notificationId()).isEqualTo("req-old");
        assertThat(legacy.response().status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(legacy.response().receivedAt()).isEqualTo(java.time.Instant.parse("2026-10-01T10:00:00.123456Z"));

        NotificationProvider provider = mock(NotificationProvider.class);
        when(provider.getProviderName()).thenReturn("smtp");
        when(provider.send(any(), any())).thenReturn(SendResult.success("msg-new"));
        NotificationResponse retry = serviceOver(provider).send(keyedRequest("req-new", "order-1-1-0"));

        assertThat(retry.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(rawResponse("order-1-1-0")).doesNotContain("john.doe");
    }

    @Test
    void rowWithFieldsFromANewerVersion_stillReads() {
        insertCompleteRow("order-newer", "req-old", FAILED_RESPONSE_1_1_0
                .replace("\"sentAt\":null", "\"sentAt\":null,\"futureField\":{\"a\":1}"));

        IdempotencyRecord rec = store.findExisting(new IdempotencyKey("acme", "billing", "order-newer")).orElseThrow();

        assertThat(rec.response().errorCode()).isEqualTo("SMTP_550");
    }

    @Test
    void markCompleteOfAFailedResponseStoresNoErrorText() {
        IdempotencyKey key = new IdempotencyKey("acme", "billing", "k-failed-text");
        store.markInProgress(key, "req-1");

        store.markComplete(key, NotificationResponse.failure(PostgresTestSupport.request("acme", "req-1"), "smtp",
                "SMTP_550", "mailbox john.doe@example.com unavailable"));

        assertThat(rawResponse("k-failed-text"))
                .doesNotContain("john.doe")
                .contains("\"errorMessage\":null")
                .contains("SMTP_550");
        assertThat(store.findExisting(key).orElseThrow().response().errorMessage()).isNull();
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
