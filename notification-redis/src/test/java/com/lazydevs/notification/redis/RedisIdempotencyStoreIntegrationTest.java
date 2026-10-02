package com.lazydevs.notification.redis;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.service.DefaultNotificationService;
import com.lazydevs.notification.core.service.NotificationAuditService;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Asserts the Redis-backed implementation honours the same DD-10 SPI
 * contracts as the in-memory one — same atomic-claim, same replay
 * semantics, same TTL behaviour. Only the storage boundary differs.
 *
 * <p>{@code FLUSHDB} is run before each test so test methods can't
 * pollute each other.
 */
@SpringBootTest(classes = TestRedisApp.class)
@TestPropertySource(properties = {
        "notification.redis.idempotency.enabled=true",
        "notification.idempotency.ttl=PT60S",
        "notification.redis.key-prefix=test-svc",
})
class RedisIdempotencyStoreIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired RedisIdempotencyStore store;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void flushRedis() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void findExisting_returnsEmpty_whenKeyAbsent() {
        Optional<IdempotencyRecord> result = store.findExisting(key("acme", "k-1"));
        assertThat(result).isEmpty();
    }

    @Test
    void markInProgress_atomicallyClaimsKey() {
        IdempotencyKey k = key("acme", "k-2");

        boolean firstWon = store.markInProgress(k, "req-001");
        boolean secondLost = store.markInProgress(k, "req-002");

        assertThat(firstWon).isTrue();
        assertThat(secondLost).isFalse();
        IdempotencyRecord rec = store.findExisting(k).orElseThrow();
        assertThat(rec.status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(rec.notificationId())
                .as("loser sees winner's notificationId — DD-10 §SPI contract")
                .isEqualTo("req-001");
    }

    @Test
    void markComplete_transitionsToCompleteAndPreservesNotificationId() {
        IdempotencyKey k = key("acme", "k-3");
        store.markInProgress(k, "req-003");
        NotificationResponse response = sentResponse("different-req-id");

        store.markComplete(k, response);

        IdempotencyRecord rec = store.findExisting(k).orElseThrow();
        assertThat(rec.status()).isEqualTo(IdempotencyStatus.COMPLETE);
        assertThat(rec.notificationId())
                .as("notificationId is the original from markInProgress, not the response's")
                .isEqualTo("req-003");
        assertThat(rec.response()).isNotNull();
        assertThat(rec.response().status()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void markComplete_withoutPriorInProgress_usesResponseRequestId() {
        // Defensive: shouldn't happen in normal flow but contract
        // should still produce a usable record.
        IdempotencyKey k = key("acme", "k-4");
        store.markComplete(k, sentResponse("req-004"));

        IdempotencyRecord rec = store.findExisting(k).orElseThrow();
        assertThat(rec.notificationId()).isEqualTo("req-004");
    }

    @Test
    void differentTenants_haveIsolatedKeys() {
        IdempotencyKey acmeKey = key("acme", "shared-id");
        IdempotencyKey otherKey = key("other-tenant", "shared-id");

        store.markInProgress(acmeKey, "req-acme");
        store.markInProgress(otherKey, "req-other");

        // Same idempotencyKey but different tenants → independent records.
        assertThat(store.findExisting(acmeKey).orElseThrow().notificationId())
                .isEqualTo("req-acme");
        assertThat(store.findExisting(otherKey).orElseThrow().notificationId())
                .isEqualTo("req-other");
    }

    @Test
    void release_afterAFailedOutcome_letsARetryClaimTheKey() {
        IdempotencyKey k = key("acme", "k-failed");
        store.markInProgress(k, "req-1");
        store.markComplete(k, failedResponse("req-1"));

        assertThat(store.release(k, "req-1")).isTrue();

        assertThat(store.findExisting(k)).isEmpty();
        assertThat(store.markInProgress(k, "req-2")).isTrue();
        assertThat(store.findExisting(k).orElseThrow().notificationId()).isEqualTo("req-2");
    }

    @Test
    void release_withAnotherNotificationId_returnsFalseAndKeepsTheRecord() {
        IdempotencyKey k = key("acme", "k-failed-2");
        store.markInProgress(k, "req-1");
        store.markComplete(k, failedResponse("req-1"));

        assertThat(store.release(k, "req-other")).isFalse();
        assertThat(store.release(k, null)).isFalse();

        assertThat(store.findExisting(k).orElseThrow().notificationId()).isEqualTo("req-1");
        assertThat(store.markInProgress(k, "req-2")).isFalse();
    }

    @Test
    void release_neverRemovesAnInProgressRecordOrAnUnreadableValue() {
        IdempotencyKey inFlight = key("acme", "k-in-flight");
        store.markInProgress(inFlight, "req-1");
        IdempotencyKey garbage = key("acme", "k-garbage");
        redis.opsForValue().set(store.redisKey(garbage), "not json");

        assertThat(store.release(inFlight, "req-1")).isFalse();
        assertThat(store.release(garbage, "req-1")).isFalse();
        assertThat(store.release(key("acme", "k-absent"), "req-1")).isFalse();

        assertThat(store.findExisting(inFlight).orElseThrow().status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(redis.opsForValue().get(store.redisKey(garbage))).isEqualTo("not json");
    }

    @Test
    void concurrentRetriesOnAFailedKey_exactlyOneWinner() throws Exception {
        // Mirrors the service's retry path: every racer releases the failed
        // record, then tries markInProgress. markInProgress stays the gate.
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 5; round++) {
                IdempotencyKey k = key("acme", "retry-race-" + round);
                store.markInProgress(k, "failed-req");
                store.markComplete(k, failedResponse("failed-req"));
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    String notificationId = "retry-" + round + "-" + i;
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        store.release(k, "failed-req");
                        return store.markInProgress(k, notificationId);
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
    void service_retriesAFailedSendUnderTheSameKey_andThenReplaysTheSuccess() {
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
    void service_releasesAFailedRecordLeftByAnOlderVersion() {
        IdempotencyKey k = key("acme", "order-legacy");
        store.markInProgress(k, "req-old");
        store.markComplete(k, failedResponse("req-old"));
        NotificationProvider provider = mock(NotificationProvider.class);
        when(provider.getProviderName()).thenReturn("smtp");
        when(provider.send(any(), any())).thenReturn(SendResult.success("msg-new"));

        NotificationResponse retry = serviceOver(provider).send(keyedRequest("req-new", "order-legacy"));

        assertThat(retry.status()).isEqualTo(NotificationStatus.SENT);
        IdempotencyRecord rec = store.findExisting(k).orElseThrow();
        assertThat(rec.notificationId()).isEqualTo("req-new");
        assertThat(rec.response().status()).isEqualTo(NotificationStatus.SENT);
    }

    /** A COMPLETE record exactly as 1.1.0 wrote it (numeric timestamps). */
    private static final String RECORD_1_1_0 = """
            {"notificationId":"req-old","status":"COMPLETE","response":{"requestId":"req-old",\
            "correlationId":"corr-old","tenantId":"acme","callerId":"billing-svc","channel":"EMAIL",\
            "provider":"smtp","status":"SENT","providerMessageId":"msg-old","errorCode":null,\
            "errorMessage":null,"receivedAt":1759312800.123456789,"processedAt":1759312800.223456789,\
            "sentAt":1759312800.223456789},"recordedAt":1759312800.323456789}""";

    @Test
    void recordWrittenBy110_stillReads() {
        IdempotencyKey k = key("acme", "k-1-1-0");
        redis.opsForValue().set(store.redisKey(k), RECORD_1_1_0);

        IdempotencyRecord rec = store.findExisting(k).orElseThrow();

        assertThat(rec.notificationId()).isEqualTo("req-old");
        assertThat(rec.status()).isEqualTo(IdempotencyStatus.COMPLETE);
        assertThat(rec.response().status()).isEqualTo(NotificationStatus.SENT);
        assertThat(rec.response().providerMessageId()).isEqualTo("msg-old");
        assertThat(rec.recordedAt()).isEqualTo(Instant.ofEpochSecond(1759312800L, 323456789L));
    }

    @Test
    void recordWithFieldsFromANewerVersion_stillReads() {
        IdempotencyKey k = key("acme", "k-newer");
        String newer = RECORD_1_1_0
                .replace("\"status\":\"SENT\",", "\"status\":\"SENT\",\"futureResponseField\":{\"a\":1},")
                .replace("\"recordedAt\":", "\"futureRecordField\":\"x\",\"recordedAt\":");
        redis.opsForValue().set(store.redisKey(k), newer);

        IdempotencyRecord rec = store.findExisting(k).orElseThrow();

        assertThat(rec.response().status()).isEqualTo(NotificationStatus.SENT);
        assertThat(rec.notificationId()).isEqualTo("req-old");
    }

    @Test
    void markComplete_ofAFailedResponse_storesNoErrorText() {
        IdempotencyKey k = key("acme", "k-failed-text");
        store.markInProgress(k, "req-1");

        store.markComplete(k, new NotificationResponse(
                "req-1", "corr-1", "acme", "billing-svc", Channel.EMAIL,
                "smtp", NotificationStatus.FAILED, null,
                "SMTP_550", "mailbox john.doe@example.com unavailable",
                Instant.now(), Instant.now(), null, null));

        assertThat(redis.opsForValue().get(store.redisKey(k)))
                .doesNotContain("john.doe")
                .contains("\"errorMessage\":null")
                .contains("SMTP_550");
        NotificationResponse stored = store.findExisting(k).orElseThrow().response();
        assertThat(stored.errorMessage()).isNull();
        assertThat(stored.errorCode()).isEqualTo("SMTP_550");
    }

    @Test
    void redisKey_includesPrefix() {
        // Reflective-free check that the prefix is honoured — useful when
        // operators share Redis across services (DD-14 §"Key namespacing").
        IdempotencyKey k = key("acme", "k-prefix");
        assertThat(store.redisKey(k)).startsWith("test-svc:idempotency:");
    }

    private static IdempotencyKey key(String tenant, String idempotencyKey) {
        return new IdempotencyKey(tenant, "billing-svc", idempotencyKey);
    }

    private static NotificationResponse failedResponse(String requestId) {
        return new NotificationResponse(
                requestId, "corr-" + requestId, "acme", "billing-svc", Channel.EMAIL,
                "smtp", NotificationStatus.FAILED, null,
                "SMTP_421", "try again later",
                Instant.now(), Instant.now(), null,
                null);
    }

    private static NotificationRequest keyedRequest(String requestId, String idempotencyKey) {
        return NotificationRequest.builder()
                .requestId(requestId)
                .tenantId("acme")
                .callerId("billing-svc")
                .notificationType("ORDER_CONFIRMATION")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .idempotencyKey(idempotencyKey)
                .build();
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

    private static NotificationResponse sentResponse(String requestId) {
        return new NotificationResponse(
                requestId, "corr-" + requestId, "acme", "billing-svc", Channel.EMAIL,
                "smtp", NotificationStatus.SENT, "msg-" + requestId,
                null, null,
                Instant.now(), Instant.now(), Instant.now(),
                null);
    }

}
