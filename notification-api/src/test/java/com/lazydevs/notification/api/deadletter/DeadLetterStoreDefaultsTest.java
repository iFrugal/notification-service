package com.lazydevs.notification.api.deadletter;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Covers the lock-free defaults of {@link DeadLetterStore#claim} and
 * {@link DeadLetterStore#release} that existing stores inherit.
 */
class DeadLetterStoreDefaultsTest {

    private static DeadLetterEntry entry(String tenantId, String requestId) {
        NotificationRequest request = NotificationRequest.builder()
                .requestId(requestId)
                .tenantId(tenantId)
                .notificationType("T")
                .channel(Channel.EMAIL)
                .build();
        return new DeadLetterEntry(Instant.now(), request,
                NotificationResponse.failure(request, "smtp", "E", "boom"),
                1, FailureType.PERMANENT);
    }

    private static DeadLetterStore storeOf(List<DeadLetterEntry> entries) {
        return new DeadLetterStore() {
            @Override
            public void add(DeadLetterEntry entry) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<List<DeadLetterEntry>> snapshot() {
                return Optional.of(entries);
            }

            @Override
            public int size() {
                return entries.size();
            }
        };
    }

    @Test
    void claimFiltersByTenantAndLimitsInSnapshotOrder() {
        DeadLetterStore store = storeOf(List.of(
                entry("a", "1"), entry("b", "2"), entry("a", "3"), entry("a", "4"), entry(null, "5")));

        assertThat(store.claim("a", 2, Duration.ofMinutes(1)))
                .extracting(e -> e.request().getRequestId())
                .containsExactly("1", "3");
        assertThat(store.claim(null, 10, Duration.ofMinutes(1)))
                .extracting(e -> e.request().getRequestId())
                .containsExactly("5");
        assertThat(store.claim("zzz", 10, Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    void claimWithNonPositiveLimitIsEmpty() {
        DeadLetterStore store = storeOf(List.of(entry("a", "1")));
        assertThat(store.claim("a", 0, Duration.ofMinutes(1))).isEmpty();
        assertThat(store.claim("a", -1, Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    void claimWithoutSnapshotIsEmpty() {
        DeadLetterStore store = new DeadLetterStore() {
            @Override
            public void add(DeadLetterEntry entry) {
                // not used
            }

            @Override
            public Optional<List<DeadLetterEntry>> snapshot() {
                return Optional.empty();
            }

            @Override
            public int size() {
                return -1;
            }
        };
        assertThat(store.claim("a", 5, Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    void releaseDefaultsToNoOp() {
        DeadLetterStore store = storeOf(List.of(entry("a", "1")));
        assertThatCode(() -> store.release("a", "1")).doesNotThrowAnyException();
        assertThat(store.claim("a", 5, Duration.ofMinutes(1))).hasSize(1);
    }
}
