package com.lazydevs.notification.rest.controller;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.caller.CallerRegistry;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the DD-15 DLQ replay endpoint
 * ({@code POST /admin/dead-letter/{requestId}/replay}).
 *
 * <p>The endpoint claims the entry before sending, removes it on success
 * (the acknowledgement) and releases it on failure, so the store mock is
 * stubbed on the targeted {@code claim} rather than {@code findByRequestId}.
 *
 * <p>Standalone {@link MockMvcBuilders#standaloneSetup} so we don't pull
 * the full Boot 4 split test-autoconfig into this module's test
 * classpath — same pattern the other controller tests use.
 */
class AdminControllerReplayTest {

    private static final Duration LEASE = Duration.ofSeconds(90);

    private NotificationService notificationService;
    private DeadLetterStore deadLetterStore;
    private NotificationProperties properties;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        deadLetterStore = mock(DeadLetterStore.class);
        properties = new NotificationProperties();
        properties.setDefaultTenant("default-tenant");
        properties.getDeadLetter().setEnabled(true);
        properties.getDeadLetter().setReplayLease(LEASE);

        AdminController controller = new AdminController(
                properties,
                mock(ProviderRegistry.class),
                mock(NotificationTemplateEngine.class),
                mock(CallerRegistry.class),
                Optional.<RateLimiter>empty(),
                Optional.of(deadLetterStore),
                Optional.<com.lazydevs.notification.api.delivery.DeliveryEventStore>empty(),
                notificationService,
                mock(com.lazydevs.notification.core.service.NotificationAuditService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void replay_happyPath_returnsNewRequestIdAndRemovesEntry() throws Exception {
        DeadLetterEntry entry = entry("req-orig", "acme");
        when(deadLetterStore.claim("acme", "req-orig", LEASE)).thenReturn(Optional.of(entry));
        when(deadLetterStore.remove("acme", "req-orig")).thenReturn(true);
        when(notificationService.send(any())).thenAnswer(inv -> sent(inv.getArgument(0)));

        mockMvc.perform(post("/api/v1/admin/dead-letter/req-orig/replay")
                        .param("tenantId", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalRequestId").value("req-orig"))
                .andExpect(jsonPath("$.replayOf").value("req-orig"))
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.removedFromDlq").value(true))
                .andExpect(jsonPath("$.newRequestId").exists());

        // Verify the request that went into send() carries replayOf and a
        // fresh idempotency key.
        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).send(captor.capture());
        NotificationRequest sent = captor.getValue();
        assertThat(sent.getReplayOf()).isEqualTo("req-orig");
        assertThat(sent.getRequestId()).isNotEqualTo("req-orig");
        assertThat(sent.getIdempotencyKey()).startsWith("replay-");
        assertThat(sent.getTenantId()).isEqualTo("acme");
        assertThat(sent.getCallerId()).isEqualTo("billing-svc");
        assertThat(sent.getChannel()).isEqualTo(Channel.EMAIL);

        verify(deadLetterStore, times(1)).remove("acme", "req-orig");
    }

    @Test
    void replay_success_claimsThenSendsThenRemoves_neverReleases() throws Exception {
        DeadLetterEntry entry = entry("req-orig", "acme");
        when(deadLetterStore.claim("acme", "req-orig", LEASE)).thenReturn(Optional.of(entry));
        when(deadLetterStore.remove("acme", "req-orig")).thenReturn(true);
        when(notificationService.send(any())).thenAnswer(inv -> sent(inv.getArgument(0)));

        mockMvc.perform(post("/api/v1/admin/dead-letter/req-orig/replay")
                        .param("tenantId", "acme"))
                .andExpect(status().isOk());

        InOrder order = inOrder(deadLetterStore, notificationService);
        order.verify(deadLetterStore).claim("acme", "req-orig", LEASE);
        order.verify(notificationService).send(any());
        order.verify(deadLetterStore).remove("acme", "req-orig");
        verify(deadLetterStore, never()).release(any(), any());
    }

    @Test
    void replay_failedSend_returns502KeepsAndReleasesEntry() throws Exception {
        DeadLetterEntry entry = entry("req-orig", "acme");
        when(deadLetterStore.claim("acme", "req-orig", LEASE)).thenReturn(Optional.of(entry));
        when(notificationService.send(any())).thenAnswer(inv -> {
            NotificationRequest req = inv.getArgument(0);
            return new NotificationResponse(
                    req.getRequestId(), null, req.getTenantId(), req.getCallerId(),
                    req.getChannel(), "smtp", NotificationStatus.FAILED, null,
                    "PROVIDER_TIMEOUT", "smtp 421 — try again later",
                    Instant.now(), Instant.now(), null, null);
        });

        mockMvc.perform(post("/api/v1/admin/dead-letter/req-orig/replay")
                        .param("tenantId", "acme"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("PROVIDER_TIMEOUT"))
                .andExpect(jsonPath("$.errorMessage").value("smtp 421 — try again later"));

        // Original entry must NOT have been removed on a failed replay —
        // operators see it's still in the queue - and its claim is given
        // back so another attempt can take it without waiting for the lease.
        verify(deadLetterStore, never()).remove(any(), any());
        verify(deadLetterStore).release("acme", "req-orig");
    }

    @Test
    void replay_unknownRequestId_returns404() throws Exception {
        when(deadLetterStore.claim(eq("acme"), eq("nope"), any(Duration.class))).thenReturn(Optional.empty());
        when(deadLetterStore.findByRequestId(eq("acme"), eq("nope"))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/admin/dead-letter/nope/replay")
                        .param("tenantId", "acme"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("No dead-letter entry")));

        verify(notificationService, never()).send(any());
        verify(deadLetterStore, never()).remove(any(), any());
        verify(deadLetterStore, never()).release(any(), any());
    }

    @Test
    void replay_entryClaimedByAnotherReplay_returns409AndSendsNothing() throws Exception {
        // The entry exists, but another replica's replay holds its lease,
        // so the targeted claim comes back empty.
        when(deadLetterStore.claim("acme", "req-orig", LEASE)).thenReturn(Optional.empty());
        when(deadLetterStore.findByRequestId("acme", "req-orig"))
                .thenReturn(Optional.of(entry("req-orig", "acme")));

        mockMvc.perform(post("/api/v1/admin/dead-letter/req-orig/replay")
                        .param("tenantId", "acme"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("CLAIMED"))
                .andExpect(jsonPath("$.originalRequestId").value("req-orig"))
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("claimed by another replay")));

        verify(notificationService, never()).send(any());
        verify(deadLetterStore, never()).remove(any(), any());
        // Not ours to release: the other replay still holds the lease.
        verify(deadLetterStore, never()).release(any(), any());
    }

    @Test
    void replay_dlqDisabled_returns503() throws Exception {
        // DLQ disabled = the AdminController was constructed without a
        // DeadLetterStore bean.
        AdminController controller = new AdminController(
                properties,
                mock(ProviderRegistry.class),
                mock(NotificationTemplateEngine.class),
                mock(CallerRegistry.class),
                Optional.<RateLimiter>empty(),
                Optional.<DeadLetterStore>empty(),
                Optional.<com.lazydevs.notification.api.delivery.DeliveryEventStore>empty(),
                notificationService,
                mock(com.lazydevs.notification.core.service.NotificationAuditService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/v1/admin/dead-letter/whatever/replay"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.enabled").value(false));

        verify(notificationService, never()).send(any());
    }

    @Test
    void replay_tenantIdDefaultsToConfiguredDefaultTenant() throws Exception {
        // No tenantId param → should resolve to properties.defaultTenant.
        DeadLetterEntry entry = entry("req-x", "default-tenant");
        when(deadLetterStore.claim("default-tenant", "req-x", LEASE)).thenReturn(Optional.of(entry));
        when(deadLetterStore.remove("default-tenant", "req-x")).thenReturn(true);
        when(notificationService.send(any())).thenAnswer(inv -> sent(inv.getArgument(0)));

        mockMvc.perform(post("/api/v1/admin/dead-letter/req-x/replay"))
                .andExpect(status().isOk());

        verify(deadLetterStore).claim("default-tenant", "req-x", LEASE);
    }

    @Test
    void replay_serviceThrows_returns500KeepsAndReleasesEntry() throws Exception {
        DeadLetterEntry entry = entry("req-orig", "acme");
        when(deadLetterStore.claim("acme", "req-orig", LEASE)).thenReturn(Optional.of(entry));
        when(notificationService.send(any())).thenThrow(new RuntimeException("kaboom"));

        mockMvc.perform(post("/api/v1/admin/dead-letter/req-orig/replay")
                        .param("tenantId", "acme"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Replay errored")));

        verify(deadLetterStore, never()).remove(any(), any());
        verify(deadLetterStore).release("acme", "req-orig");
    }

    @Test
    void replay_usesTheConfiguredLease() throws Exception {
        properties.getDeadLetter().setReplayLease(Duration.ofMinutes(17));
        when(deadLetterStore.claim(anyString(), anyString(), any(Duration.class))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/admin/dead-letter/req-orig/replay")
                        .param("tenantId", "acme"))
                .andExpect(status().isNotFound());

        verify(deadLetterStore).claim("acme", "req-orig", Duration.ofMinutes(17));
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private static NotificationResponse sent(NotificationRequest req) {
        return new NotificationResponse(
                req.getRequestId(), null, req.getTenantId(), req.getCallerId(),
                req.getChannel(), "smtp", NotificationStatus.SENT, "msg-1",
                null, null, Instant.now(), Instant.now(), Instant.now(), null);
    }

    private static DeadLetterEntry entry(String requestId, String tenantId) {
        NotificationRequest req = NotificationRequest.builder()
                .requestId(requestId)
                .tenantId(tenantId)
                .callerId("billing-svc")
                .notificationType("ORDER_CONFIRMATION")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .templateData(java.util.Map.of("name", "Pat"))
                .build();
        NotificationResponse resp = new NotificationResponse(
                requestId, null, tenantId, "billing-svc", Channel.EMAIL,
                "smtp", NotificationStatus.FAILED, null,
                "ERR", "boom",
                Instant.now(), Instant.now(), null, null);
        return new DeadLetterEntry(Instant.now(), req, resp, 3, FailureType.TRANSIENT);
    }
}
