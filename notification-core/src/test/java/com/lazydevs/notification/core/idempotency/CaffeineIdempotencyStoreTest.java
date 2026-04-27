package com.lazydevs.notification.core.idempotency;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.core.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CaffeineIdempotencyStoreTest {

    private NotificationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.getIdempotency().setEnabled(true);
        properties.getIdempotency().setStore("caffeine");
        properties.getIdempotency().setTtl(Duration.ofMinutes(10));
        properties.getIdempotency().setMaxEntries(10_000L);
    }

    @Test
    void findExisting_returnsEmpty_whenKeyAbsent() {
        CaffeineIdempotencyStore store = new CaffeineIdempotencyStore(properties);

        Optional<IdempotencyRecord> result = store.findExisting(key("acme", "order-1"));

        assertThat(result).isEmpty();
    }

    @Test
    void markInProgress_returnsTrue_andRecordsInProgress() {
        CaffeineIdempotencyStore store = new CaffeineIdempotencyStore(properties);
        IdempotencyKey key = key("acme", "order-2");

        boolean won = store.markInProgress(key, "req-001");

        assertThat(won).isTrue();
        Optional<IdempotencyRecord> record = store.findExisting(key);
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(record.get().notificationId()).isEqualTo("req-001");
        assertThat(record.get().response()).isNull();
    }

    @Test
    void markInProgress_returnsFalse_whenKeyAlreadyExists() {
        CaffeineIdempotencyStore store = new CaffeineIdempotencyStore(properties);
        IdempotencyKey key = key("acme", "order-3");
        store.markInProgress(key, "req-first");

        boolean wonSecond = store.markInProgress(key, "req-second");

        assertThat(wonSecond).isFalse();
        // Original notificationId is preserved — that's what 409 callers will see.
        assertThat(store.findExisting(key).orElseThrow().notificationId()).isEqualTo("req-first");
    }

    @Test
    void markComplete_transitionsToComplete_andCarriesResponse() {
        CaffeineIdempotencyStore store = new CaffeineIdempotencyStore(properties);
        IdempotencyKey key = key("acme", "order-4");
        store.markInProgress(key, "req-004");
        NotificationResponse response = sentResponse("req-004");

        store.markComplete(key, response);

        IdempotencyRecord record = store.findExisting(key).orElseThrow();
        assertThat(record.status()).isEqualTo(IdempotencyStatus.COMPLETE);
        assertThat(record.response()).isSameAs(response);
        // notificationId is preserved from the in-progress record, not from the response.
        assertThat(record.notificationId()).isEqualTo("req-004");
    }

    @Test
    void markComplete_withoutPriorInProgress_usesResponseRequestIdAsNotificationId() {
        // This shouldn't happen in normal flow but the contract should still
        // produce a usable record — defensive handling.
        CaffeineIdempotencyStore store = new CaffeineIdempotencyStore(properties);
        IdempotencyKey key = key("acme", "order-5");

        store.markComplete(key, sentResponse("req-005"));

        IdempotencyRecord record = store.findExisting(key).orElseThrow();
        assertThat(record.notificationId()).isEqualTo("req-005");
        assertThat(record.status()).isEqualTo(IdempotencyStatus.COMPLETE);
    }

    @Test
    void concurrent_markInProgress_exactlyOneWinner() throws Exception {
        CaffeineIdempotencyStore store = new CaffeineIdempotencyStore(properties);
        IdempotencyKey key = key("acme", "race-key");
        int threads = 32;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(threads);
        AtomicInteger winners = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                String requestId = "req-" + i;
                pool.submit(() -> {
                    try {
                        start.await();
                        if (store.markInProgress(key, requestId)) {
                            winners.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finish.countDown();
                    }
                });
            }
            start.countDown();
            finish.await();
        } finally {
            pool.shutdownNow();
        }

        assertThat(winners.get())
                .as("Exactly one of %d concurrent threads should have won the markInProgress race", threads)
                .isEqualTo(1);
        assertThat(store.findExisting(key)).isPresent();
    }

    @Test
    void ttl_expiresEntries() {
        properties.getIdempotency().setTtl(Duration.ofMillis(50));
        CaffeineIdempotencyStore store = new CaffeineIdempotencyStore(properties);
        IdempotencyKey key = key("acme", "ttl-key");
        store.markInProgress(key, "req-ttl");

        // Wait beyond TTL. Caffeine's expireAfterWrite is honoured opportunistically,
        // so we explicitly evict to force the cleanup to run promptly.
        await().pollDelay(Duration.ofMillis(150))
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    store.evictExpired();
                    assertThat(store.findExisting(key)).isEmpty();
                });
    }

    @Test
    void maxEntries_evictsBeyondBound() {
        properties.getIdempotency().setMaxEntries(2);
        CaffeineIdempotencyStore store = new CaffeineIdempotencyStore(properties);
        IdempotencyKey k1 = key("acme", "k1");
        IdempotencyKey k2 = key("acme", "k2");
        IdempotencyKey k3 = key("acme", "k3");

        store.markInProgress(k1, "r1");
        store.markInProgress(k2, "r2");
        store.markInProgress(k3, "r3");
        // Caffeine's size-based eviction is asynchronous; cleanUp() forces it.
        store.evictExpired();

        // Caffeine doesn't promise *which* entries are evicted under maxSize,
        // only that the total stabilises near (not strictly at) the bound.
        // Asserting "at most 2 of the 3 keys are present" gives us a stable
        // contract without depending on eviction ordering.
        long present = java.util.stream.Stream.of(k1, k2, k3)
                .filter(k -> store.findExisting(k).isPresent())
                .count();
        assertThat(present).isLessThanOrEqualTo(2);
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private static IdempotencyKey key(String tenant, String idempotencyKey) {
        return new IdempotencyKey(tenant, null, idempotencyKey);
    }

    private static NotificationResponse sentResponse(String requestId) {
        return new NotificationResponse(
                requestId, "corr-" + requestId, "acme", Channel.EMAIL,
                "smtp", NotificationStatus.SENT, "msg-" + requestId,
                null, null,
                Instant.now(), Instant.now(), Instant.now(),
                null);
    }
}
