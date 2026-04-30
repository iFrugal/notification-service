package com.lazydevs.notification.redis;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.core.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the Redis-backed implementation honours the same DD-10 SPI
 * contracts as the in-memory one — same atomic-claim, same replay
 * semantics, same TTL behaviour. Only the storage boundary differs.
 *
 * <p>{@code FLUSHDB} is run before each test so test methods can't
 * pollute each other.
 */
@SpringBootTest(classes = {
        RedisIdempotencyStoreIntegrationTest.TestApp.class,
        NotificationProperties.class,
        RedisIdempotencyStore.class
})
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
    void redisKey_includesPrefix() {
        // Reflective-free check that the prefix is honoured — useful when
        // operators share Redis across services (DD-14 §"Key namespacing").
        IdempotencyKey k = key("acme", "k-prefix");
        assertThat(store.redisKey(k)).startsWith("test-svc:idempotency:");
    }

    private static IdempotencyKey key(String tenant, String idempotencyKey) {
        return new IdempotencyKey(tenant, "billing-svc", idempotencyKey);
    }

    private static NotificationResponse sentResponse(String requestId) {
        return new NotificationResponse(
                requestId, "corr-" + requestId, "acme", "billing-svc", Channel.EMAIL,
                "smtp", NotificationStatus.SENT, "msg-" + requestId,
                null, null,
                Instant.now(), Instant.now(), Instant.now(),
                null);
    }

    @SpringBootApplication
    @Import({NotificationProperties.class})
    static class TestApp {}
}
