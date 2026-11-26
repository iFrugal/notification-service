package com.lazydevs.notification.core.audit;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.audit.NotificationAuditStore;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationAudit;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.api.model.SmsRecipient;
import com.lazydevs.notification.api.model.WhatsAppRecipient;
import lazydevs.persistence.connection.multitenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoreBackedAuditServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private final NotificationAuditStore store = mock(NotificationAuditStore.class);
    private final StoreBackedAuditService service =
            new StoreBackedAuditService(store, "default", Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void resetTenant() {
        TenantContext.reset();
    }

    @Test
    void recordReceived_buildsAMaskedRecordWithCountsAndTheCallersMetadata() {
        NotificationRequest request = NotificationRequest.builder()
                .requestId("r-1").correlationId("c-1").tenantId("acme").callerId("billing")
                .idempotencyKey("k-1").replayOf("r-0").notificationType("WELCOME").channel(Channel.EMAIL)
                .provider("smtp").templateId("welcome")
                .recipient(new EmailRecipient(null, "jane@example.com", List.of("a@example.com", " "),
                        List.of("b@example.com"), null, null))
                .metadata(Map.of("source", "test"))
                .build();

        NotificationAudit audit = service.recordReceived(request);

        ArgumentCaptor<NotificationAudit> stored = ArgumentCaptor.forClass(NotificationAudit.class);
        verify(store).insert(stored.capture());
        assertThat(stored.getValue()).isSameAs(audit);
        assertThat(audit.getRecipientSummary()).isEqualTo("to=j***@example.com cc=2 bcc=1").doesNotContain("jane@");
        assertThat(audit.getRecipientCount()).isEqualTo(3);
        assertThat(audit.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(audit.getReceivedAt()).isEqualTo(NOW);
        assertThat(audit.getUpdatedAt()).isEqualTo(NOW);
        assertThat(audit.getMetadata()).containsExactly(Map.entry("source", "test"));
        assertThat(audit).extracting(NotificationAudit::getIdempotencyKey, NotificationAudit::getCallerId,
                        NotificationAudit::getReplayOf, NotificationAudit::getTemplateId, NotificationAudit::getProvider)
                .containsExactly("k-1", "billing", "r-0", "welcome", "smtp");
        assertThat(audit.getId()).isNotBlank();
    }

    @Test
    void storeFailuresOnWritesAreSwallowed() {
        doThrow(new IllegalStateException("db down")).when(store).insert(any());
        when(store.recordOutcome(any())).thenThrow(new IllegalStateException("db down"));
        NotificationRequest request = NotificationRequest.builder().requestId("r-1").tenantId("acme")
                .channel(Channel.SMS).recipient(new SmsRecipient(null, "+15551234567")).build();

        assertThat(service.recordReceived(request)).isNotNull();
        service.recordOutcome(NotificationResponse.failure(request, null, "E", "m"));
    }

    @Test
    void legacyUpdateStatusAndFind_useTheContextTenantOrTheDefault() {
        when(store.find(any(), any())).thenReturn(Optional.empty());

        service.updateStatus("r-1", NotificationStatus.SENT, "m-1", null, null);
        TenantContext.setTenantId("acme");
        service.findByRequestId("r-2");

        ArgumentCaptor<NotificationResponse> outcome = ArgumentCaptor.forClass(NotificationResponse.class);
        verify(store).recordOutcome(outcome.capture());
        assertThat(outcome.getValue().tenantId()).isEqualTo("default");
        assertThat(outcome.getValue().providerMessageId()).isEqualTo("m-1");
        verify(store).find("default", "r-1");
        verify(store).find("acme", "r-2");
    }

    @Test
    void readsDelegateToTheStore() {
        NotificationAudit audit = NotificationAudit.builder().requestId("r").build();
        when(store.find("t", "r")).thenReturn(Optional.of(audit));
        when(store.findByIdempotencyKey("t", "c", "k")).thenReturn(Optional.of(audit));
        when(store.findRecent("t", 5)).thenReturn(List.of(audit));

        assertThat(service.findByRequestId("t", "r")).containsSame(audit);
        assertThat(service.findByIdempotencyKey("t", "c", "k")).containsSame(audit);
        assertThat(service.findRecent("t", 5)).contains(List.of(audit));
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void recipientCount_perRecipientType() {
        assertThat(StoreBackedAuditService.recipientCount(null)).isNull();
        assertThat(StoreBackedAuditService.recipientCount(new EmailRecipient(null, null, null, null, null, null)))
                .isZero();
        assertThat(StoreBackedAuditService.recipientCount(new SmsRecipient(null, "+15551234567"))).isEqualTo(1);
        assertThat(StoreBackedAuditService.recipientCount(
                new WhatsAppRecipient(null, "+15551234567", null, null, null, null, null))).isEqualTo(1);
        assertThat(StoreBackedAuditService.recipientCount(push("tok", null, null, null))).isEqualTo(1);
        assertThat(StoreBackedAuditService.recipientCount(push(null, "fid-1", null, null))).isEqualTo(1);
        assertThat(StoreBackedAuditService.recipientCount(push(null, null, Arrays.asList("a", "", "b"), null)))
                .isEqualTo(2);
        assertThat(StoreBackedAuditService.recipientCount(push(null, null, List.of(), "news"))).isNull();
    }

    private static PushRecipient push(String token, String fid, List<String> tokens, String topic) {
        return new PushRecipient(null, token, topic, null, null, null, null, null, null, null, null, fid, tokens);
    }

}
