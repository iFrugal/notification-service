package com.lazydevs.notification.redis;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.PushRecipient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the Redis-backed DLQ honours the same DD-13 SPI contract as
 * the in-memory one — same insertion order (most recent first), same
 * bounded eviction, same never-throw safety on serialisation issues.
 */
@SpringBootTest(classes = TestRedisApp.class)
@TestPropertySource(properties = {
        "notification.dead-letter.enabled=true",
        "notification.redis.dead-letter.enabled=true",
        "notification.redis.dead-letter.max-entries=3",
        "notification.redis.key-prefix=test-dlq",
})
class RedisDeadLetterStoreIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired RedisDeadLetterStore store;
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
    void recordedEntries_returnInMostRecentFirstOrder() {
        store.add(entry("req-1"));
        store.add(entry("req-2"));
        store.add(entry("req-3"));

        Optional<List<DeadLetterEntry>> snap = store.snapshot();
        assertThat(snap).isPresent();
        assertThat(snap.get())
                .extracting(e -> e.request().getRequestId())
                .as("LPUSH semantics — most recent first")
                .containsExactly("req-3", "req-2", "req-1");
        assertThat(store.size()).isEqualTo(3);
    }

    @Test
    void boundedCapacity_evictsBeyondMax() {
        // maxEntries=3 from the @TestPropertySource. After 5 LPUSHes,
        // LTRIM keeps only the 3 newest.
        for (int i = 1; i <= 5; i++) {
            store.add(entry("req-" + i));
        }

        Optional<List<DeadLetterEntry>> snap = store.snapshot();
        assertThat(snap).isPresent();
        assertThat(snap.get())
                .extracting(e -> e.request().getRequestId())
                .containsExactly("req-5", "req-4", "req-3");
        assertThat(store.size()).isEqualTo(3);
    }

    @Test
    void operatorCanReadKeyWithRedisCli() {
        // DD-14 §"Why JSON serialization": operators should be able to
        // run redis-cli LRANGE and read entries directly. Sanity-check
        // that the raw value is human-readable JSON.
        store.add(entry("req-readable"));
        List<String> raws = redis.opsForList().range("test-dlq:dlq", 0, 0);
        assertThat(raws).hasSize(1);
        assertThat(raws.get(0))
                .startsWith("{")
                .contains("\"req-readable\"")
                .contains("\"failureType\":\"TRANSIENT\"");
    }

    @Test
    void entryFromANewerVersion_withAnUnknownFailureTypeAndExtraFields_stillReads() {
        // What a 1.2 node could write: a FailureType constant 1.1.x lacks and
        // fields 1.1.x does not know, including inside a push recipient.
        redis.opsForList().leftPush("test-dlq:dlq", """
                {"timestamp":1759312800.000000000,"request":{"requestId":"req-push","tenantId":"acme",\
                "callerId":"billing","notificationType":"TEST","channel":"PUSH","recipient":{"type":"PUSH",\
                "id":null,"deviceToken":"device-token-1","title":"Hi","body":"There","data":{},\
                "futureRecipientField":"x"},"futureRequestField":true},"response":{"requestId":"req-push",\
                "tenantId":"acme","channel":"PUSH","status":"FAILED","errorCode":"FCM_UNAVAILABLE",\
                "futureResponseField":1},"attempts":3,"failureType":"SOMETHING_NEW","futureEntryField":"x"}""");

        List<DeadLetterEntry> entries = store.snapshot().orElseThrow();

        assertThat(entries).hasSize(1);
        DeadLetterEntry entry = entries.get(0);
        assertThat(entry.failureType()).isEqualTo(FailureType.UNKNOWN);
        assertThat(entry.attempts()).isEqualTo(3);
        assertThat(entry.request().getRecipient()).isInstanceOfSatisfying(PushRecipient.class,
                push -> assertThat(push.deviceToken()).isEqualTo("device-token-1"));
        assertThat(store.findByRequestId("acme", "req-push")).isPresent();
    }

    private static DeadLetterEntry entry(String requestId) {
        NotificationRequest req = NotificationRequest.builder()
                .requestId(requestId)
                .tenantId("acme")
                .callerId("billing")
                .notificationType("TEST")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .build();
        NotificationResponse resp = new NotificationResponse(
                requestId, null, "acme", "billing", Channel.EMAIL,
                "smtp", NotificationStatus.FAILED, null, "ERR", "boom",
                Instant.now(), Instant.now(), null, null);
        return new DeadLetterEntry(Instant.now(), req, resp, 3, FailureType.TRANSIENT);
    }

}
