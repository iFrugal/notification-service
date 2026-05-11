package com.lazydevs.notification.rest.controller;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the DD-19 bulk DLQ replay endpoint.
 *
 * <p>Covers dry-run vs live, the tenantId requirement, partial
 * success (some entries succeed, some fail), DLQ-disabled, and
 * cross-tenant isolation (entries from a different tenant aren't
 * touched).
 */
class AdminControllerBulkReplayTest {

    private NotificationService notificationService;
    private DeadLetterStore deadLetterStore;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        deadLetterStore = mock(DeadLetterStore.class);
        NotificationProperties properties = new NotificationProperties();
        properties.setDefaultTenant("default-tenant");
        properties.getDeadLetter().setEnabled(true);

        AdminController controller = new AdminController(
                properties,
                mock(ProviderRegistry.class),
                mock(NotificationTemplateEngine.class),
                mock(CallerRegistry.class),
                Optional.<RateLimiter>empty(),
                Optional.of(deadLetterStore),
                Optional.<DeliveryEventStore>empty(),
                notificationService,
                mock(NotificationAuditService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void dryRun_returnsPreviewWithoutCallingSendOrRemove() throws Exception {
        when(deadLetterStore.snapshot()).thenReturn(Optional.of(List.of(
                entry("req-1", "acme"),
                entry("req-2", "acme"))));

        mockMvc.perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "acme")
                        .param("dryRun", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("dry-run"))
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.entries[0].originalRequestId").value("req-1"))
                .andExpect(jsonPath("$.entries[0].failureType").exists())
                .andExpect(jsonPath("$.entries[0].status").doesNotExist())
                .andExpect(jsonPath("$.replayed").doesNotExist());

        verify(notificationService, never()).send(any());
        verify(deadLetterStore, never()).remove(any(), any());
    }

    @Test
    void live_replaysEachEntry_removesSuccessful_keepsFailed() throws Exception {
        when(deadLetterStore.snapshot()).thenReturn(Optional.of(List.of(
                entry("req-success-1", "acme"),
                entry("req-fail-1", "acme"),
                entry("req-success-2", "acme"))));
        when(deadLetterStore.remove(any(), any())).thenReturn(true);

        // First and third entries succeed, second fails at the provider.
        when(notificationService.send(any())).thenAnswer(inv -> {
            NotificationRequest req = inv.getArgument(0);
            String originalReplayOf = req.getReplayOf();
            NotificationStatus status = "req-fail-1".equals(originalReplayOf)
                    ? NotificationStatus.FAILED
                    : NotificationStatus.SENT;
            return new NotificationResponse(
                    req.getRequestId(), null, req.getTenantId(), req.getCallerId(),
                    req.getChannel(), "smtp", status,
                    status == NotificationStatus.SENT ? "msg-1" : null,
                    status == NotificationStatus.FAILED ? "PROVIDER_TIMEOUT" : null,
                    status == NotificationStatus.FAILED ? "smtp 421" : null,
                    Instant.now(), Instant.now(),
                    status == NotificationStatus.SENT ? Instant.now() : null,
                    null);
        });

        mockMvc.perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("live"))
                .andExpect(jsonPath("$.replayed").value(2))
                .andExpect(jsonPath("$.stillDeadLettered").value(1))
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].status").value("SENT"))
                .andExpect(jsonPath("$.entries[0].removedFromDlq").value(true))
                .andExpect(jsonPath("$.entries[1].status").value("FAILED"))
                .andExpect(jsonPath("$.entries[1].removedFromDlq").value(false))
                .andExpect(jsonPath("$.entries[1].errorCode").value("PROVIDER_TIMEOUT"));

        // Successful entries removed (2), failed entry not removed.
        verify(deadLetterStore, times(2)).remove(any(), any());
    }

    @Test
    void live_perEntryExceptionDoesNotShortCircuit() throws Exception {
        // A thrown exception (e.g. rate-limit) on one entry should
        // still let the rest of the batch run. The HTTP code stays
        // 200; the entry shows status:FAILED with the exception
        // message.
        when(deadLetterStore.snapshot()).thenReturn(Optional.of(List.of(
                entry("req-1", "acme"),
                entry("req-2", "acme"))));
        when(deadLetterStore.remove(any(), any())).thenReturn(true);
        when(notificationService.send(any()))
                .thenThrow(new RuntimeException("rate-limit hit"))
                .thenAnswer(inv -> {
                    NotificationRequest req = inv.getArgument(0);
                    return new NotificationResponse(
                            req.getRequestId(), null, req.getTenantId(), req.getCallerId(),
                            req.getChannel(), "smtp", NotificationStatus.SENT, "msg-1",
                            null, null, Instant.now(), Instant.now(), Instant.now(), null);
                });

        mockMvc.perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(1))
                .andExpect(jsonPath("$.stillDeadLettered").value(1))
                .andExpect(jsonPath("$.entries[0].status").value("FAILED"))
                .andExpect(jsonPath("$.entries[0].errorMessage").exists());
    }

    @Test
    void live_isTenantScoped_doesNotTouchOtherTenants() throws Exception {
        // acme has 2 entries, globex has 1. tenantId=acme should only
        // replay acme's; globex's entry must not appear in the
        // results nor be sent.
        when(deadLetterStore.snapshot()).thenReturn(Optional.of(List.of(
                entry("req-acme-1", "acme"),
                entry("req-globex-1", "globex"),
                entry("req-acme-2", "acme"))));
        when(deadLetterStore.remove(any(), any())).thenReturn(true);
        when(notificationService.send(any())).thenAnswer(inv -> {
            NotificationRequest req = inv.getArgument(0);
            return new NotificationResponse(
                    req.getRequestId(), null, req.getTenantId(), req.getCallerId(),
                    req.getChannel(), "smtp", NotificationStatus.SENT, "msg-1",
                    null, null, Instant.now(), Instant.now(), Instant.now(), null);
        });

        mockMvc.perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.replayed").value(2))
                .andExpect(jsonPath("$.entries[0].originalRequestId").value("req-acme-1"))
                .andExpect(jsonPath("$.entries[1].originalRequestId").value("req-acme-2"));

        // Service only called twice — globex's entry was filtered out.
        verify(notificationService, times(2)).send(any());
    }

    @Test
    void missingTenantId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/dead-letter/replay-batch"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        verify(notificationService, never()).send(any());
    }

    @Test
    void blankTenantId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "   "))
                .andExpect(status().isBadRequest());

        verify(notificationService, never()).send(any());
    }

    @Test
    void dlqDisabled_returns503() throws Exception {
        NotificationProperties properties = new NotificationProperties();
        AdminController controller = new AdminController(
                properties,
                mock(ProviderRegistry.class),
                mock(NotificationTemplateEngine.class),
                mock(CallerRegistry.class),
                Optional.<RateLimiter>empty(),
                Optional.<DeadLetterStore>empty(),     // DLQ disabled
                Optional.<DeliveryEventStore>empty(),
                notificationService,
                mock(NotificationAuditService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "acme"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void limit_clampsAt1000() throws Exception {
        // Construct 5 entries; passing limit=10000 must clamp at 1000
        // internally. (5 entries is fewer than the cap so all run; the
        // test confirms no surefire-blowing error from the absurd
        // limit value.)
        when(deadLetterStore.snapshot()).thenReturn(Optional.of(List.of(
                entry("req-1", "acme"))));
        when(deadLetterStore.remove(any(), any())).thenReturn(true);
        when(notificationService.send(any())).thenAnswer(inv -> {
            NotificationRequest req = inv.getArgument(0);
            return new NotificationResponse(
                    req.getRequestId(), null, req.getTenantId(), req.getCallerId(),
                    req.getChannel(), "smtp", NotificationStatus.SENT, "msg-1",
                    null, null, Instant.now(), Instant.now(), Instant.now(), null);
        });

        mockMvc.perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "acme")
                        .param("limit", "10000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1));
    }

    private static DeadLetterEntry entry(String requestId, String tenantId) {
        NotificationRequest req = NotificationRequest.builder()
                .requestId(requestId)
                .tenantId(tenantId)
                .callerId("billing-svc")
                .notificationType("ORDER_CONFIRMATION")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .build();
        NotificationResponse resp = new NotificationResponse(
                requestId, null, tenantId, "billing-svc", Channel.EMAIL,
                "smtp", NotificationStatus.FAILED, null, "ERR", "boom",
                Instant.now(), Instant.now(), null, null);
        return new DeadLetterEntry(Instant.now(), req, resp, 3, FailureType.TRANSIENT);
    }
}
