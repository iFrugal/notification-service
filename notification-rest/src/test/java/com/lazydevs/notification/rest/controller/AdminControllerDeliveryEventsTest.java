package com.lazydevs.notification.rest.controller;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.caller.CallerRegistry;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.provider.ProviderRegistry;
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
 * Tests for the DD-17 delivery events admin endpoint.
 *
 * <p>Standalone {@link MockMvcBuilders#standaloneSetup} so we don't
 * pull the full Boot 4 split test-autoconfig into this module's test
 * classpath — same pattern the other admin tests use.
 */
class AdminControllerDeliveryEventsTest {

    private DeliveryEventStore deliveryEventStore;
    private NotificationProperties properties;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        deliveryEventStore = mock(DeliveryEventStore.class);
        properties = new NotificationProperties();
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
                mock(com.lazydevs.notification.core.service.NotificationAuditService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void snapshot_returnsRecentEvents() throws Exception {
        when(deliveryEventStore.size()).thenReturn(2);
        when(deliveryEventStore.snapshot()).thenReturn(Optional.of(List.of(
                event("ses-2", "ses", DeliveryStatus.BOUNCED),
                event("ses-1", "ses", DeliveryStatus.DELIVERED))));

        mockMvc.perform(get("/api/v1/admin/delivery-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.maxEntries").value(5000))
                .andExpect(jsonPath("$.entries[0].providerMessageId").value("ses-2"))
                .andExpect(jsonPath("$.entries[0].status").value("BOUNCED"))
                .andExpect(jsonPath("$.entries[1].providerMessageId").value("ses-1"))
                .andExpect(jsonPath("$.entries[1].status").value("DELIVERED"));

        // findByProviderMessageId should NOT have been called — no
        // filter params present.
        verify(deliveryEventStore, never()).findByProviderMessageId(any(), any());
    }

    @Test
    void filterByProviderMessageId_callsLookupRatherThanSnapshot() throws Exception {
        when(deliveryEventStore.size()).thenReturn(42);
        when(deliveryEventStore.findByProviderMessageId("ses", "ses-1"))
                .thenReturn(Optional.of(List.of(
                        event("ses-1", "ses", DeliveryStatus.COMPLAINED),
                        event("ses-1", "ses", DeliveryStatus.DELIVERED))));

        mockMvc.perform(get("/api/v1/admin/delivery-events")
                        .param("providerName", "ses")
                        .param("providerMessageId", "ses-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].providerMessageId").value("ses-1"));

        verify(deliveryEventStore).findByProviderMessageId("ses", "ses-1");
        verify(deliveryEventStore, never()).snapshot();
    }

    @Test
    void attributesOmittedByDefault_includedWithIncludeRaw() throws Exception {
        when(deliveryEventStore.size()).thenReturn(1);
        DeliveryEvent withAttrs = new DeliveryEvent(
                Instant.now(), "twilio", "SM1", "AC1:SM1:delivered",
                DeliveryStatus.DELIVERED, null,
                Map.of("to", "+15551234567", "messagesid", "SM1"));
        when(deliveryEventStore.snapshot()).thenReturn(Optional.of(List.of(withAttrs)));

        // Default: no `attributes` in the response — PII redaction
        // (DD-17 §"PII-safe attribute redaction").
        mockMvc.perform(get("/api/v1/admin/delivery-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].providerMessageId").value("SM1"))
                .andExpect(jsonPath("$.entries[0].attributes").doesNotExist());

        // With includeRaw=true, the attribute map is surfaced.
        mockMvc.perform(get("/api/v1/admin/delivery-events")
                        .param("includeRaw", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].attributes.to").value("+15551234567"));
    }

    @Test
    void storeDisabled_returns503() throws Exception {
        // Re-build with the store optional empty.
        AdminController controller = new AdminController(
                properties,
                mock(ProviderRegistry.class),
                mock(NotificationTemplateEngine.class),
                mock(CallerRegistry.class),
                Optional.<RateLimiter>empty(),
                Optional.<DeadLetterStore>empty(),
                Optional.<DeliveryEventStore>empty(),
                mock(NotificationService.class),
                mock(com.lazydevs.notification.core.service.NotificationAuditService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/v1/admin/delivery-events"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("delivery-events.enabled=true")));
    }

    @Test
    void limitIsClamped() throws Exception {
        when(deliveryEventStore.size()).thenReturn(2);
        when(deliveryEventStore.snapshot()).thenReturn(Optional.of(List.of(
                event("ses-2", "ses", DeliveryStatus.BOUNCED),
                event("ses-1", "ses", DeliveryStatus.DELIVERED))));

        // Negative / zero / huge values should not break the endpoint.
        mockMvc.perform(get("/api/v1/admin/delivery-events").param("limit", "-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1));  // clamped to 1

        mockMvc.perform(get("/api/v1/admin/delivery-events").param("limit", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2));  // capped to 1000 internally, still 2 returned
    }

    @Test
    void filterWithOnlyProviderName_fallsBackToSnapshot() throws Exception {
        // Both filter params must be present to trigger the lookup
        // form; a partial filter is operator error — fall back to the
        // full snapshot rather than throwing.
        when(deliveryEventStore.size()).thenReturn(1);
        when(deliveryEventStore.snapshot()).thenReturn(Optional.of(List.of(
                event("ses-1", "ses", DeliveryStatus.DELIVERED))));

        mockMvc.perform(get("/api/v1/admin/delivery-events")
                        .param("providerName", "ses"))
                .andExpect(status().isOk());

        verify(deliveryEventStore).snapshot();
        verify(deliveryEventStore, never()).findByProviderMessageId(any(), any());
    }

    @Test
    void backendReturnsEmptyOptional_responseFlagsIt() throws Exception {
        // Hypothetical backend that can't iterate cheaply — same shape
        // the DLQ snapshot endpoint handles.
        when(deliveryEventStore.size()).thenReturn(-1);
        when(deliveryEventStore.snapshot()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/delivery-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").doesNotExist())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("does not support snapshot iteration")));
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
