package com.lazydevs.notification.core.audit;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationAudit;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryNotificationAuditStoreTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private final InMemoryNotificationAuditStore store =
            new InMemoryNotificationAuditStore(100, Duration.ofDays(1), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void insertAndFind_areScopedByTenant_andCopied() {
        Map<String, String> metadata = new HashMap<>(Map.of("a", "1"));
        NotificationAudit audit = audit("acme", "r-1", null, NOW).toBuilder().metadata(metadata).build();
        store.insert(audit);
        audit.setStatus(NotificationStatus.FAILED);
        metadata.put("b", "2");

        NotificationAudit read = store.find("acme", "r-1").orElseThrow();
        assertThat(read.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(read.getMetadata()).containsOnlyKeys("a");
        read.setStatus(NotificationStatus.SENT);
        assertThat(store.find("acme", "r-1").orElseThrow().getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(store.find("globex", "r-1")).isEmpty();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void recordOutcome_appliesTheOutcome_orReportsNoRecord() {
        store.insert(audit("acme", "r-1", null, NOW).toBuilder().provider("smtp").build());
        NotificationResponse failed = NotificationResponse.failure(request("acme", "r-1"), null, "E", "[email]")
                .withFailureType(FailureType.PERMANENT);

        assertThat(store.recordOutcome(failed)).isTrue();
        NotificationAudit read = store.find("acme", "r-1").orElseThrow();
        assertThat(read.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(read.getProvider()).isEqualTo("smtp");
        assertThat(read.getFailureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(read.getFailedAt()).isEqualTo(failed.processedAt());
        assertThat(read.getUpdatedAt()).isEqualTo(NOW);

        assertThat(store.recordOutcome(NotificationResponse.failure(request("acme", "nope"), null, "E", "m")))
                .isFalse();
    }

    @Test
    void findByIdempotencyKey_returnsTheLatestOfTheScope() {
        store.insert(audit("acme", "r-1", "k", NOW).toBuilder().callerId("billing").build());
        store.insert(audit("acme", "r-2", "k", NOW.plusSeconds(1)).toBuilder().callerId("billing").build());
        store.insert(audit("acme", "r-3", "k", NOW.plusSeconds(2)).toBuilder().callerId(null).build());

        assertThat(store.findByIdempotencyKey("acme", "billing", "k").orElseThrow().getRequestId()).isEqualTo("r-2");
        assertThat(store.findByIdempotencyKey("acme", null, "k").orElseThrow().getRequestId()).isEqualTo("r-3");
        assertThat(store.findByIdempotencyKey("globex", "billing", "k")).isEmpty();
    }

    @Test
    void findRecent_isNewestFirst_boundedAndTenantScoped() {
        for (int i = 0; i < 5; i++) {
            store.insert(audit("acme", "r-" + i, null, NOW.plusSeconds(i)));
        }
        store.insert(audit("globex", "g", null, NOW));
        store.insert(audit("acme", "no-time", null, null));

        assertThat(store.findRecent("acme", 2)).extracting(NotificationAudit::getRequestId)
                .containsExactly("r-4", "r-3");
        assertThat(store.findRecent("acme", 0)).isEmpty();
        assertThat(store.findRecent("acme", 10)).last().extracting(NotificationAudit::getRequestId)
                .isEqualTo("no-time");
    }

    private static NotificationAudit audit(String tenant, String requestId, String key, Instant receivedAt) {
        return NotificationAudit.builder()
                .tenantId(tenant).requestId(requestId).idempotencyKey(key)
                .status(NotificationStatus.PROCESSING).receivedAt(receivedAt).build();
    }

    private static NotificationRequest request(String tenant, String requestId) {
        return NotificationRequest.builder().tenantId(tenant).requestId(requestId).channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "a@example.com", null, null, null, null)).build();
    }
}
