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
    //  DD-15 — findByRequestId / remove
    // -----------------------------------------------------------------

    @Test
    void findByRequestId_returnsEntryWhenPresent() {
        InMemoryDeadLetterStore store = new InMemoryDeadLetterStore(properties);
        store.add(entry("req-1", FailureType.TRANSIENT, 3));
        store.add(entry("req-2", FailureType.PERMANENT, 1));

        Optional<DeadLetterEntry> found = store.findByRequestId("acme", "req-2");
        assertThat(found).isPresent();
        assertThat(found.get().request().getRequestId()).isEqualTo("req-2");
    }

    @Test
    void findByRequestId_isTenantScoped() {
        // Two tenants both submit "req-1" — DD-15 §"Why tenant in the
        // key": requestIds are caller-generated, only unique within a
        // tenant. Cross-tenant collision must NOT leak.
        InMemoryDeadLetterStore store = new InMemoryDeadLetterStore(properties);
        store.add(entryFor("req-1", "acme"));
        store.add(entryFor("req-1", "globex"));

        assertThat(store.findByRequestId("acme", "req-1"))
                .isPresent()
                .get()
                .satisfies(e -> assertThat(e.request().getTenantId()).isEqualTo("acme"));
        assertThat(store.findByRequestId("globex", "req-1"))
                .isPresent()
                .get()
                .satisfies(e -> assertThat(e.request().getTenantId()).isEqualTo("globex"));
        assertThat(store.findByRequestId("widgets", "req-1")).isEmpty();
    }

    @Test
    void findByRequestId_returnsEmptyForUnknownRequestId() {
        InMemoryDeadLetterStore store = new InMemoryDeadLetterStore(properties);
        store.add(entry("req-1", FailureType.TRANSIENT, 3));

        assertThat(store.findByRequestId("acme", "nope")).isEmpty();
        assertThat(store.findByRequestId(null, "req-1")).isEmpty();
        assertThat(store.findByRequestId("acme", null)).isEmpty();
    }

    @Test
    void remove_deletesEntryAndReturnsTrue() {
        InMemoryDeadLetterStore store = new InMemoryDeadLetterStore(properties);
        store.add(entry("req-1", FailureType.TRANSIENT, 3));
        store.add(entry("req-2", FailureType.PERMANENT, 1));

        assertThat(store.remove("acme", "req-1")).isTrue();
        assertThat(store.findByRequestId("acme", "req-1")).isEmpty();
        // The other entry stays intact.
        assertThat(store.findByRequestId("acme", "req-2")).isPresent();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void remove_isIdempotentAgainstRepeatedCalls() {
        InMemoryDeadLetterStore store = new InMemoryDeadLetterStore(properties);
        store.add(entry("req-1", FailureType.TRANSIENT, 3));

        assertThat(store.remove("acme", "req-1")).isTrue();
        assertThat(store.remove("acme", "req-1"))
                .as("second remove() of the same key should be a no-op returning false")
                .isFalse();
    }

    @Test
    void remove_isTenantScoped() {
        InMemoryDeadLetterStore store = new InMemoryDeadLetterStore(properties);
        store.add(entryFor("req-1", "acme"));
        store.add(entryFor("req-1", "globex"));

        assertThat(store.remove("globex", "req-1")).isTrue();
        // acme's req-1 untouched.
        assertThat(store.findByRequestId("acme", "req-1")).isPresent();
        assertThat(store.findByRequestId("globex", "req-1")).isEmpty();
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private static DeadLetterEntry entry(String requestId, FailureType ft, int attempts) {
        return entryWith(requestId, "acme", ft, attempts);
    }

    private static DeadLetterEntry entryFor(String requestId, String tenantId) {
        return entryWith(requestId, tenantId, FailureType.TRANSIENT, 1);
    }

    private static DeadLetterEntry entryWith(String requestId, String tenantId,
                                             FailureType ft, int attempts) {
        NotificationRequest req = NotificationRequest.builder()
                .requestId(requestId)
                .tenantId(tenantId)
                .callerId("billing")
                .notificationType("TEST")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .build();
        NotificationResponse resp = new NotificationResponse(
                requestId, null, tenantId, "billing", Channel.EMAIL,
                "smtp", NotificationStatus.FAILED, null,
                "ERR", "boom",
                Instant.now(), Instant.now(), null, null);
        return new DeadLetterEntry(Instant.now(), req, resp, attempts, ft);
    }
}
