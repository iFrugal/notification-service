package com.lazydevs.notification.rest.controller;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.model.NotificationAudit;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.caller.CallerRegistry;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.service.NotificationAuditService;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the DD-18 {@code ?requestId} filter on
 * {@code GET /admin/delivery-events}. The four documented outcomes
 * (DD-18 §"Edge cases"):
 * <ul>
 *   <li>Audit not found → 404</li>
 *   <li>Audit found, providerMessageId null → 200 + auditState=incomplete</li>
 *   <li>Audit found, providerMessageId set, no events → 200 + auditState=complete</li>
 *   <li>Audit found, providerMessageId set, events present → 200 + auditState=complete</li>
 * </ul>
 */
class AdminControllerDeliveryEventsByRequestIdTest {

    private DeliveryEventStore deliveryEventStore;
    private NotificationAuditService auditService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        deliveryEventStore = mock(DeliveryEventStore.class);
        auditService = mock(NotificationAuditService.class);
        NotificationProperties properties = new NotificationProperties();
        properties.getDeliveryEvents().setEnabled(true);
        properties.getDeliveryEvents().setMaxEntries(5_000);

        AdminController controller = new AdminController(
                properties,
                mock(ProviderRegistry.class),
                mock(NotificationTemplateEngine.class),
                mock(CallerRegistry.class),
                Optional.<RateLimiter>empty(),
                Optional.<DeadLetterStore>empty(),
                Optional.of(deliveryEventStore),
                mock(NotificationService.class),
                auditService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void byRequestId_auditNotFound_returns404() throws Exception {
        when(auditService.findByRequestId("req-missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/delivery-events").param("requestId", "req-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());

        verify(deliveryEventStore, never()).findByProviderMessageId(any(), any());
        verify(deliveryEventStore, never()).snapshot();
    }

    @Test
    void byRequestId_auditFoundButIncomplete_returnsIncompleteState() throws Exception {
        // Send hasn't completed yet — audit row exists but
        // provider/providerMessageId not stamped. DD-18 §"Edge cases":
        // distinguish this from "complete-but-no-events-yet".
        NotificationAudit audit = NotificationAudit.builder()
                .requestId("req-pending")
                .tenantId("acme")
                .channel(Channel.EMAIL)
                .status(NotificationStatus.PROCESSING)
                .build();
        when(auditService.findByRequestId("req-pending")).thenReturn(Optional.of(audit));

        mockMvc.perform(get("/api/v1/admin/delivery-events").param("requestId", "req-pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditState").value("incomplete"))
                .andExpect(jsonPath("$.entries.length()").value(0))
                .andExpect(jsonPath("$.message").exists());

        verify(deliveryEventStore, never()).findByProviderMessageId(any(), any());
    }

    @Test
    void byRequestId_auditComplete_noEvents_returnsCompleteWithEmpty() throws Exception {
        // Send completed (provider + providerMessageId stamped) but no
        // delivery callback has arrived. Common during the window
        // between provider accept and the first webhook.
        NotificationAudit audit = NotificationAudit.builder()
                .requestId("req-complete-quiet")
                .tenantId("acme")
                .channel(Channel.EMAIL)
                .provider("ses")
                .providerMessageId("ses-msg-1")
                .status(NotificationStatus.SENT)
                .build();
        when(auditService.findByRequestId("req-complete-quiet")).thenReturn(Optional.of(audit));
        when(deliveryEventStore.findByProviderMessageId("ses", "ses-msg-1"))
                .thenReturn(Optional.of(List.of()));

        mockMvc.perform(get("/api/v1/admin/delivery-events").param("requestId", "req-complete-quiet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditState").value("complete"))
                .andExpect(jsonPath("$.providerMessageId").value("ses-msg-1"))
                .andExpect(jsonPath("$.provider").value("ses"))
                .andExpect(jsonPath("$.entries.length()").value(0));
    }

    @Test
    void byRequestId_auditCompleteWithEvents_returnsThem() throws Exception {
        NotificationAudit audit = NotificationAudit.builder()
                .requestId("req-with-events")
                .tenantId("acme")
                .channel(Channel.SMS)
                .provider("twilio")
                .providerMessageId("SM1abc")
                .status(NotificationStatus.SENT)
                .build();
        when(auditService.findByRequestId("req-with-events")).thenReturn(Optional.of(audit));
        when(deliveryEventStore.findByProviderMessageId("twilio", "SM1abc"))
                .thenReturn(Optional.of(List.of(
                        event("SM1abc", "twilio", DeliveryStatus.DELIVERED))));

        mockMvc.perform(get("/api/v1/admin/delivery-events").param("requestId", "req-with-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditState").value("complete"))
                .andExpect(jsonPath("$.providerMessageId").value("SM1abc"))
                .andExpect(jsonPath("$.provider").value("twilio"))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].providerMessageId").value("SM1abc"))
                .andExpect(jsonPath("$.entries[0].status").value("DELIVERED"));

        verify(deliveryEventStore).findByProviderMessageId("twilio", "SM1abc");
        verify(deliveryEventStore, never()).snapshot();
    }

    @Test
    void byRequestId_takesPrecedenceOverProviderTuple() throws Exception {
        // DD-18 §"Filter precedence": ?requestId beats
        // ?providerName + ?providerMessageId when both are supplied.
        // The join is the stricter scope.
        NotificationAudit audit = NotificationAudit.builder()
                .requestId("req-x")
                .tenantId("acme")
                .channel(Channel.EMAIL)
                .provider("ses")
                .providerMessageId("ses-from-audit")
                .status(NotificationStatus.SENT)
                .build();
        when(auditService.findByRequestId("req-x")).thenReturn(Optional.of(audit));
        when(deliveryEventStore.findByProviderMessageId("ses", "ses-from-audit"))
                .thenReturn(Optional.of(List.of(
                        event("ses-from-audit", "ses", DeliveryStatus.DELIVERED))));

        mockMvc.perform(get("/api/v1/admin/delivery-events")
                        .param("requestId", "req-x")
                        .param("providerName", "twilio")              // ignored
                        .param("providerMessageId", "should-not-use")) // ignored
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerMessageId").value("ses-from-audit"));

        // Verify the audit-supplied tuple was used, not the request params.
        verify(deliveryEventStore).findByProviderMessageId("ses", "ses-from-audit");
    }

    @Test
    void byRequestId_blankRequestId_fallsBackToSnapshot() throws Exception {
        // A blank ?requestId= shouldn't trigger the join; treat it as
        // "no filter supplied" — same forgiving pattern the existing
        // providerTuple branch uses.
        when(deliveryEventStore.size()).thenReturn(0);
        when(deliveryEventStore.snapshot()).thenReturn(Optional.of(List.of()));

        mockMvc.perform(get("/api/v1/admin/delivery-events").param("requestId", "   "))
                .andExpect(status().isOk());

        verify(auditService, never()).findByRequestId(any());
        verify(deliveryEventStore).snapshot();
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
                Map.of("source", "test"));
    }
}
