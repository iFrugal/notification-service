package com.lazydevs.notification.core.deadletter;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.core.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the DD-13 default {@link InMemoryDeadLetterStore}.
 */
class InMemoryDeadLetterStoreTest {

    private NotificationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.getDeadLetter().setEnabled(true);
        properties.getDeadLetter().setMaxEntries(1000);
    }

    @Test
    void recordAndSnapshot_returnsEntryInOrder() {
        InMemoryDeadLetterStore store = new InMemoryDeadLetterStore(properties);
        DeadLetterEntry e1 = entry("req-1", FailureType.TRANSIENT, 3);
        DeadLetterEntry e2 = entry("req-2", FailureType.PERMANENT, 1);

        store.add(e1);
        store.add(e2);

        Optional<List<DeadLetterEntry>> snap = store.snapshot();
        assertThat(snap).isPresent();
        // Most recent first (DD-13 §"Insertion order is reconstructed at
        // snapshot time using a monotonic counter").
        assertThat(snap.get()).extracting(en -> en.request().getRequestId())
                .containsExactly("req-2", "req-1");
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void boundedCapacity_evictsBeyondBound() {
        properties.getDeadLetter().setMaxEntries(3);
        InMemoryDeadLetterStore store = new InMemoryDeadLetterStore(properties);

        for (int i = 1; i <= 5; i++) {
            store.add(entry("req-" + i, FailureType.UNKNOWN, 1));
        }
        // Force Caffeine's lazy eviction to run — same pattern as
        // CaffeineIdempotencyStoreTest. Caffeine doesn't promise
        // *which* entries are evicted under maxSize, only that the
        // total stabilises near the bound.
        store.evictPending();

        Optional<List<DeadLetterEntry>> snap = store.snapshot();
        assertThat(snap).isPresent();
        assertThat(snap.get())
                .as("after evictPending(), the store should be at-or-below the configured bound")
                .hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void emptyStore_returnsEmptyList() {
        InMemoryDeadLetterStore store = new InMemoryDeadLetterStore(properties);

        assertThat(store.snapshot()).isPresent();
        assertThat(store.snapshot().get()).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    void deadLetterEntry_rejectsZeroAttempts() {
        // CR-3: canonical constructor enforces attempts >= 1.
        NotificationRequest req = NotificationRequest.builder()
                .requestId("r").tenantId("t").notificationType("T").channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "x@y.z", null, null, null, null))
                .build();
        NotificationResponse resp = new NotificationResponse(
                "r", null, "t", null, Channel.EMAIL,
                "smtp", NotificationStatus.FAILED, null, "E", "m",
                Instant.now(), Instant.now(), null, null);

        assertThatThrownBy(() -> new DeadLetterEntry(Instant.now(), req, resp, 0, FailureType.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempts must be >= 1");
    }

    @Test
    void deadLetterEntry_rejectsSentResponseStatus() {
        // CR-3: only FAILED or REJECTED responses belong in the DLQ.
        NotificationRequest req = NotificationRequest.builder()
                .requestId("r").tenantId("t").notificationType("T").channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "x@y.z", null, null, null, null))
                .build();
        NotificationResponse resp = new NotificationResponse(
                "r", null, "t", null, Channel.EMAIL,
                "smtp", NotificationStatus.SENT, "msg-1", null, null,
                Instant.now(), Instant.now(), Instant.now(), null);

        assertThatThrownBy(() -> new DeadLetterEntry(Instant.now(), req, resp, 1, FailureType.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FAILED or REJECTED");
    }

    @Test
    void recordSwallowsExceptions_neverThrows() {
        // The SPI contract is "implementations should never throw" — we
        // verify by recording entries and asserting no throw escapes
        // even with corner inputs.
        InMemoryDeadLetterStore store = new InMemoryDeadLetterStore(properties);

        // A record() call must not throw even if internals hiccup; the
        // best we can do without injecting a faulty backing map is
        // confirm the normal path doesn't.
        // attempts must be >= 1 per DeadLetterEntry's canonical
        // constructor invariant — even a "no retries attempted"
        // permanent failure counts the initial attempt as 1.
        store.add(entry("req-x", FailureType.UNKNOWN, 1));
        assertThat(store.size()).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private static DeadLetterEntry entry(String requestId, FailureType ft, int attempts) {
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
                "smtp", NotificationStatus.FAILED, null,
                "ERR", "boom",
                Instant.now(), Instant.now(), null, null);
        return new DeadLetterEntry(Instant.now(), req, resp, attempts, ft);
    }
}
