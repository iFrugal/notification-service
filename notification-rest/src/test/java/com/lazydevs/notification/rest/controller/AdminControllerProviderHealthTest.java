package com.lazydevs.notification.rest.controller;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.caller.CallerRegistry;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.provider.ProviderRegistry.PendingState;
import com.lazydevs.notification.core.service.NotificationAuditService;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /admin/health} with lazily initialised providers (DD-29): a
 * provider no send has needed yet is listed as {@code NOT_INITIALIZED} and
 * keeps the status {@code UP}; one whose last build failed is listed as
 * {@code INIT_FAILED} and makes it {@code DEGRADED}.
 */
class AdminControllerProviderHealthTest {

    private ProviderRegistry registry;
    private AdminController controller;

    @BeforeEach
    void setUp() {
        registry = mock(ProviderRegistry.class);
        NotificationProvider healthy = mock(NotificationProvider.class);
        when(healthy.isHealthy()).thenReturn(true);
        when(registry.getAllProviders()).thenReturn(Map.of("acme:email:smtp", healthy));
        controller = new AdminController(
                new NotificationProperties(),
                registry,
                mock(NotificationTemplateEngine.class),
                mock(CallerRegistry.class),
                Optional.<RateLimiter>empty(),
                Optional.<DeadLetterStore>empty(),
                Optional.<DeliveryEventStore>empty(),
                mock(NotificationService.class),
                mock(NotificationAuditService.class));
    }

    @Test
    void notYetInitialized_isListedAndKeepsTheStatusUp() {
        when(registry.getUninitializedProviders())
                .thenReturn(Map.of("acme:sms:twilio", PendingState.NOT_INITIALIZED));

        Map<String, Object> body = controller.health().getBody();

        assertThat(body).containsEntry("status", "UP");
        assertThat(body).extracting("providers").asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("acme:email:smtp", "HEALTHY")
                .containsEntry("acme:sms:twilio", "NOT_INITIALIZED");
    }

    @Test
    void failedInitialization_isListedAndDegradesTheStatus() {
        when(registry.getUninitializedProviders())
                .thenReturn(Map.of("acme:sms:twilio", PendingState.INIT_FAILED));

        Map<String, Object> body = controller.health().getBody();

        assertThat(body).containsEntry("status", "DEGRADED");
        assertThat(body).extracting("providers").asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("acme:sms:twilio", "INIT_FAILED");
    }
}
