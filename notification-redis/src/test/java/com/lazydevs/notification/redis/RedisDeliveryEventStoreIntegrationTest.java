package com.lazydevs.notification.redis;

import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the Redis-backed {@code DeliveryEventStore} (DD-17) honours
 * the same SPI contract as the in-memory default — most-recent-first
 * ordering, bounded eviction via {@code LTRIM}, provider-scoped
 * lookup.
 *
 * <p>Mirrors {@code RedisDeadLetterStoreIntegrationTest} since the two
 * Redis stores share the same {@code LPUSH + LTRIM + LRANGE} pattern
 * (DD-17 §"Why mirror the Redis pattern").
 */
@SpringBootTest(classes = TestRedisApp.class)
@TestPropertySource(properties = {
        "notification.redis.delivery-events.enabled=true",
        "notification.redis.delivery-events.max-entries=3",
        "notification.redis.key-prefix=test-delivery",
})
class RedisDeliveryEventStoreIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired RedisDeliveryEventStore store;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void flushRedis() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void emptyStore_returnsEmptySnapshot() {
        assertThat(store.snapshot()).isPresent();
        assertThat(store.snapshot().get()).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    void recordedEvents_returnInMostRecentFirstOrder() {
        store.add(event("ses-1", "ses", DeliveryStatus.DELIVERED));
        store.add(event("ses-2", "ses", DeliveryStatus.BOUNCED));
        store.add(event("ses-3", "ses", DeliveryStatus.COMPLAINED));

        Optional<List<DeliveryEvent>> snap = store.snapshot();
        assertThat(snap).isPresent();
        assertThat(snap.get())
                .extracting(DeliveryEvent::providerMessageId)
                .as("LPUSH semantics — most recent first")
                .containsExactly("ses-3", "ses-2", "ses-1");
        assertThat(store.size()).isEqualTo(3);
    }

    @Test
    void boundedCapacity_evictsBeyondMax() {
        // max-entries=3 from the @TestPropertySource. After 5 LPUSHes,
        // LTRIM keeps only the 3 newest.
        for (int i = 1; i <= 5; i++) {
            store.add(event("msg-" + i, "ses", DeliveryStatus.DELIVERED));
        }

        Optional<List<DeliveryEvent>> snap = store.snapshot();
        assertThat(snap).isPresent();
        assertThat(snap.get())
                .extracting(DeliveryEvent::providerMessageId)
                .containsExactly("msg-5", "msg-4", "msg-3");
        assertThat(store.size()).isEqualTo(3);
    }

    @Test
    void findByProviderMessageId_returnsAllMatchingEvents() {
        // Same providerMessageId can appear multiple times — e.g. a
        // BOUNCED followed by a COMPLAINED for the same notification.
        store.add(event("ses-1", "ses", DeliveryStatus.DELIVERED));
        store.add(event("ses-2", "ses", DeliveryStatus.DELIVERED));
        store.add(event("ses-1", "ses", DeliveryStatus.COMPLAINED));

        // max-entries=3 so all three fit. Sequence in Redis (head-first
        // after the three LPUSHes): [ses-1/COMP, ses-2/DELIV, ses-1/DELIV]
        Optional<List<DeliveryEvent>> found = store.findByProviderMessageId("ses", "ses-1");
        assertThat(found).isPresent();
        assertThat(found.get())
                .extracting(DeliveryEvent::status)
                .containsExactly(DeliveryStatus.COMPLAINED, DeliveryStatus.DELIVERED);
    }

    @Test
    void findByProviderMessageId_isProviderScoped() {
        // DD-17 §SPI: same providerMessageId from different providers
        // must not collide.
        store.add(event("msg-1", "ses", DeliveryStatus.DELIVERED));
        store.add(event("msg-1", "twilio", DeliveryStatus.BOUNCED));

        assertThat(store.findByProviderMessageId("ses", "msg-1"))
                .isPresent()
                .get()
                .satisfies(list -> {
                    assertThat(list).hasSize(1);
                    assertThat(list.get(0).providerName()).isEqualTo("ses");
                });
        assertThat(store.findByProviderMessageId("twilio", "msg-1"))
                .isPresent()
                .get()
                .satisfies(list -> {
                    assertThat(list).hasSize(1);
                    assertThat(list.get(0).providerName()).isEqualTo("twilio");
                });
    }

    @Test
    void operatorCanReadKeyWithRedisCli() {
        // DD-14 §"Why JSON serialization": operators should be able to
        // run redis-cli LRANGE and read entries directly. Sanity-check
        // the raw value is human-readable JSON.
        store.add(event("ses-readable", "ses", DeliveryStatus.DELIVERED));
        List<String> raws = redis.opsForList().range("test-delivery:delivery-events", 0, 0);
        assertThat(raws).hasSize(1);
        assertThat(raws.get(0))
                .startsWith("{")
                .contains("\"providerMessageId\":\"ses-readable\"")
                .contains("\"status\":\"DELIVERED\"");
    }

    private static DeliveryEvent event(String providerMessageId, String providerName,
                                       DeliveryStatus status) {
        return new DeliveryEvent(
                Instant.now(),
                providerName,
                providerMessageId,
                providerName + ":" + providerMessageId + ":" + status.name(),
                status,
                null,
                Map.of("source", "integration-test"));
    }
}
