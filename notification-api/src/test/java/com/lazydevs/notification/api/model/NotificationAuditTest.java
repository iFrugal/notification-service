package com.lazydevs.notification.api.model;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DD-33 additions to {@link NotificationAudit} and {@link NotificationView}.
 */
class NotificationAuditTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    @Test
    void applyOutcome_sent_thenFailed_thenSentAgain() {
        NotificationAudit audit = NotificationAudit.builder().provider("smtp").providerMessageId("old").build();
        NotificationRequest request = NotificationRequest.builder().requestId("r").tenantId("t")
                .channel(Channel.EMAIL).build();

        audit.applyOutcome(NotificationResponse.sent(request, null, null, NOW, NOW), NOW);
        assertThat(audit.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(audit.getProvider()).isEqualTo("smtp");
        assertThat(audit.getProviderMessageId()).isEqualTo("old");
        assertThat(audit.getSentAt()).isEqualTo(NOW);
        assertThat(audit.getFailedAt()).isNull();

        NotificationResponse failed = new NotificationResponse("r", null, "t", null, Channel.EMAIL, "ses",
                NotificationStatus.REJECTED, "m-2", "E", "msg", NOW, null, null, null, FailureType.PERMANENT, null, null);
        Instant later = NOW.plusSeconds(5);
        audit.applyOutcome(failed, later);
        assertThat(audit.getProvider()).isEqualTo("ses");
        assertThat(audit.getProviderMessageId()).isEqualTo("m-2");
        assertThat(audit.getProcessedAt()).as("defaults to now").isEqualTo(later);
        assertThat(audit.getFailedAt()).isEqualTo(later);
        assertThat(audit.getFailureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(audit.getUpdatedAt()).isEqualTo(later);
        assertThat(audit.getSentAt()).as("kept").isEqualTo(NOW);
    }

    @Test
    void isFailure() {
        assertThat(NotificationAudit.isFailure(NotificationStatus.FAILED)).isTrue();
        assertThat(NotificationAudit.isFailure(NotificationStatus.REJECTED)).isTrue();
        assertThat(NotificationAudit.isFailure(NotificationStatus.SENT)).isFalse();
        assertThat(NotificationAudit.isFailure(null)).isFalse();
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyConstructorLeavesTheNewFieldsNull() {
        NotificationAudit audit = new NotificationAudit("id", "r", "c", "t", "caller", "r0", "TYPE", Channel.SMS,
                "twilio", "phone=+1***90", NotificationStatus.SENT, "m", null, null, NOW, NOW, NOW, null,
                Map.of("k", "v"), "tpl", null, null);

        assertThat(audit.getRequestId()).isEqualTo("r");
        assertThat(audit.getReplayOf()).isEqualTo("r0");
        assertThat(audit.getTemplateId()).isEqualTo("tpl");
        assertThat(audit.getIdempotencyKey()).isNull();
        assertThat(audit.getRecipientCount()).isNull();
        assertThat(audit.getFailureType()).isNull();
        assertThat(audit.getFailedAt()).isNull();
        assertThat(audit.getUpdatedAt()).isNull();
    }

    @Test
    void viewCopiesItsMetadata() {
        Map<String, String> metadata = new HashMap<>(Map.of("a", "1"));
        NotificationView view = new NotificationView("r", "t", null, null, null, Channel.EMAIL, "T", null, null,
                1, metadata, NotificationStatus.SENT, null, null, null, null, NOW, NOW, null, null, NOW, null, null);
        metadata.put("b", "2");

        assertThat(view.metadata()).containsOnlyKeys("a");
        assertThatThrownBy(() -> view.metadata().put("c", "3")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new NotificationView("r", "t", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null).metadata()).isNull();
    }
}
