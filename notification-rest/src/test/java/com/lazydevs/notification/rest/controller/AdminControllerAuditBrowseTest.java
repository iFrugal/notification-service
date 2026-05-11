package com.lazydevs.notification.rest.controller;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
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
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the DD-20 admin audit browse endpoints
 * ({@code GET /admin/audit/{requestId}} +
 * {@code GET /admin/audit/recent}).
 */
class AdminControllerAuditBrowseTest {

    private NotificationAuditService auditService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        auditService = mock(NotificationAuditService.class);
        NotificationProperties properties = new NotificationProperties();

        AdminController controller = new AdminController(
                properties,
                mock(ProviderRegistry.class),
                mock(NotificationTemplateEngine.class),
                mock(CallerRegistry.class),
                Optional.<RateLimiter>empty(),
                Optional.<DeadLetterStore>empty(),
                Optional.<DeliveryEventStore>empty(),
                mock(NotificationService.class),
                auditService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void single_returnsAuditRecord_whenFound() throws Exception {
        NotificationAudit audit = NotificationAudit.builder()
                .requestId("req-abc")
                .tenantId("acme")
                .callerId("billing-svc")
                .channel(Channel.EMAIL)
                .provider("ses")
                .providerMessageId("ses-msg-1")
                .recipientSummary("j***@example.com")
                .status(NotificationStatus.SENT)
                .receivedAt(Instant.parse("2026-05-11T10:00:00Z"))
                .sentAt(Instant.parse("2026-05-11T10:00:01Z"))
                .build();
        when(auditService.findByRequestId("req-abc")).thenReturn(Optional.of(audit));

        mockMvc.perform(get("/api/v1/admin/audit/req-abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-abc"))
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.callerId").value("billing-svc"))
                .andExpect(jsonPath("$.channel").value("EMAIL"))
                .andExpect(jsonPath("$.provider").value("ses"))
                .andExpect(jsonPath("$.providerMessageId").value("ses-msg-1"))
                .andExpect(jsonPath("$.recipientSummary").value("j***@example.com"))
                .andExpect(jsonPath("$.status").value("SENT"));
    }

    @Test
    void single_returns404_whenNotFound() throws Exception {
        when(auditService.findByRequestId("req-missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/audit/req-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void recent_returnsListWhenBackendSupports() throws Exception {
        NotificationAudit a = NotificationAudit.builder()
                .requestId("req-1").tenantId("acme").channel(Channel.SMS)
                .status(NotificationStatus.SENT).build();
        NotificationAudit b = NotificationAudit.builder()
                .requestId("req-2").tenantId("acme").channel(Channel.EMAIL)
                .status(NotificationStatus.FAILED).build();
        when(auditService.findRecent("acme", 50)).thenReturn(Optional.of(List.of(a, b)));

        mockMvc.perform(get("/api/v1/admin/audit/recent").param("tenantId", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].requestId").value("req-1"))
                .andExpect(jsonPath("$.entries[1].requestId").value("req-2"));
    }

    @Test
    void recent_noBackendSupport_returns200WithNullEntries() throws Exception {
        // NoOpAuditService default — findRecent returns
        // Optional.empty(). Endpoint surfaces this as 200 with
        // entries=null + explanatory message.
        when(auditService.findRecent("acme", 50)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/audit/recent").param("tenantId", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").doesNotExist())  // null serialises as absent
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void recent_missingTenantId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit/recent"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recent_blankTenantId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit/recent").param("tenantId", "   "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recent_limitIsClampedAt200() throws Exception {
        // limit=10000 should clamp to 200 internally — the SPI call
        // sees the clamped value.
        when(auditService.findRecent("acme", 200)).thenReturn(Optional.of(List.of()));

        mockMvc.perform(get("/api/v1/admin/audit/recent")
                        .param("tenantId", "acme")
                        .param("limit", "10000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(200));
    }

    @Test
    void recent_limitFloorIs1() throws Exception {
        when(auditService.findRecent("acme", 1)).thenReturn(Optional.of(List.of()));

        mockMvc.perform(get("/api/v1/admin/audit/recent")
                        .param("tenantId", "acme")
                        .param("limit", "-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(1));
    }
}
