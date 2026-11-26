package com.lazydevs.notification.core.push;

import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEvents;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.push.PushDeviceRegistration;
import com.lazydevs.notification.api.push.PushDeviceRegistry;
import com.lazydevs.notification.api.push.PushDeviceRemovalReasons;
import com.lazydevs.notification.api.push.PushPlatform;
import com.lazydevs.notification.api.push.PushTokens;
import lazydevs.persistence.connection.multitenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushDeviceCleanupListenerTest {

    private final InMemoryPushDeviceRegistry registry =
            new InMemoryPushDeviceRegistry(10, Duration.ofDays(60), Clock.systemUTC());
    private final PushDeviceCleanupListener listener = new PushDeviceCleanupListener(registry);

    @AfterEach
    void resetTenant() {
        TenantContext.reset();
    }

    private static DeliveryEvent event(DeliveryStatus status, String reason, Map<String, String> attributes) {
        return new DeliveryEvent(Instant.now(), "fcm", "m-1", "e-1", status, reason, attributes);
    }

    private static DeliveryEvent invalidTarget(String token) {
        return event(DeliveryStatus.BOUNCED, DeliveryEvents.REASON_INVALID_TARGET,
                Map.of(DeliveryEvents.ATTR_TOKEN_HASH, PushTokens.hash(token)));
    }

    @Test
    void anInvalidTargetBounce_removesTheDevice_inTheCurrentTenantScope() {
        TenantContext.setTenantId("acme");
        registry.register(PushDeviceRegistration.of("u1", "inst-1", PushPlatform.ANDROID, "dead"));
        registry.register(PushDeviceRegistration.of("u1", "inst-2", PushPlatform.ANDROID, "alive"));
        TenantContext.setTenantId("globex");
        registry.register(PushDeviceRegistration.of("u1", "inst-1", PushPlatform.ANDROID, "dead"));

        TenantContext.setTenantId("acme");
        listener.onEvent(invalidTarget("dead"));

        assertThat(registry.activeDevices("u1", null)).extracting("token").containsExactly("alive");
        TenantContext.setTenantId("globex");
        assertThat(registry.activeDevices("u1", null)).extracting("token").containsExactly("dead");
    }

    @Test
    void otherEvents_areIgnored() {
        PushDeviceRegistry mocked = mock(PushDeviceRegistry.class);
        PushDeviceCleanupListener ignoring = new PushDeviceCleanupListener(mocked);
        String hash = PushTokens.hash("t");

        ignoring.onEvent(null);
        ignoring.onEvent(event(DeliveryStatus.DELIVERED, DeliveryEvents.REASON_INVALID_TARGET,
                Map.of(DeliveryEvents.ATTR_TOKEN_HASH, hash)));
        ignoring.onEvent(event(DeliveryStatus.BOUNCED, "MAILBOX_FULL", Map.of(DeliveryEvents.ATTR_TOKEN_HASH, hash)));
        ignoring.onEvent(event(DeliveryStatus.BOUNCED, DeliveryEvents.REASON_INVALID_TARGET, Map.of()));
        ignoring.onEvent(event(DeliveryStatus.BOUNCED, DeliveryEvents.REASON_INVALID_TARGET,
                Map.of(DeliveryEvents.ATTR_TOKEN_HASH, "sha256:NOT-A-HASH")));

        org.mockito.Mockito.verifyNoInteractions(mocked);
    }

    @Test
    void aRegistryFailure_isSwallowed() {
        PushDeviceRegistry failing = mock(PushDeviceRegistry.class);
        when(failing.removeByTokenHash(anyString(), anyString())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> new PushDeviceCleanupListener(failing).onEvent(invalidTarget("t"))).doesNotThrowAnyException();
        verify(failing).removeByTokenHash(PushTokens.hash("t"), PushDeviceRemovalReasons.INVALID_TARGET);
    }
}
