package com.lazydevs.notification.core.health;

import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.config.NotificationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the DD-21 health indicators. Each indicator is
 * tested in isolation against a mocked SPI bean.
 *
 * <p>The "indicator absent when SPI absent" case is enforced by
 * Spring's {@code @ConditionalOnBean} at wire time, not by the
 * indicator logic — so it's not tested here. The Boot context loads
 * either include or skip the indicator based on bean presence.
 */
class HealthIndicatorsTest {

    // -----------------------------------------------------------------
    //  DeadLetterStoreHealthIndicator
    // -----------------------------------------------------------------

    @Test
    void dlqIndicator_reportsUpAtLowFill() {
        DeadLetterStore store = mock(DeadLetterStore.class);
        when(store.size()).thenReturn(47);

        NotificationProperties props = new NotificationProperties();
        props.getDeadLetter().setMaxEntries(1_000);
        props.getHealth().setDlqNearFullPercent(80);

        Health h = new DeadLetterStoreHealthIndicator(store, props).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails())
                .containsEntry("size", 47)
                .containsEntry("maxEntries", 1_000)
                .containsEntry("fillPercent", 4)
                .containsEntry("nearFullPercent", 80);
    }

    @Test
    void dlqIndicator_flipsToOutOfServiceAtThreshold() {
        // 850/1000 = 85% — past the 80% threshold. Status.OUT_OF_SERVICE
        // is the correct nuance: DLQ is still answering (not DOWN), but
        // operators want their monitoring to alert.
        DeadLetterStore store = mock(DeadLetterStore.class);
        when(store.size()).thenReturn(850);

        NotificationProperties props = new NotificationProperties();
        props.getDeadLetter().setMaxEntries(1_000);
        props.getHealth().setDlqNearFullPercent(80);

        Health h = new DeadLetterStoreHealthIndicator(store, props).health();

        assertThat(h.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(h.getDetails()).containsEntry("fillPercent", 85);
    }

    @Test
    void dlqIndicator_nearFullPercentZeroDisablesThreshold() {
        // Operators may set 0 in dev to silence the alert. A 100%-full
        // DLQ still reports UP.
        DeadLetterStore store = mock(DeadLetterStore.class);
        when(store.size()).thenReturn(1_000);

        NotificationProperties props = new NotificationProperties();
        props.getDeadLetter().setMaxEntries(1_000);
        props.getHealth().setDlqNearFullPercent(0);

        Health h = new DeadLetterStoreHealthIndicator(store, props).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void dlqIndicator_handlesSizeUnavailable() {
        // SPI contract: size() may return -1 when the backend can't
        // answer cheaply. The indicator reports UP with size: unavailable
        // rather than computing a misleading fill percent from -1.
        DeadLetterStore store = mock(DeadLetterStore.class);
        when(store.size()).thenReturn(-1);

        NotificationProperties props = new NotificationProperties();
        props.getDeadLetter().setMaxEntries(1_000);

        Health h = new DeadLetterStoreHealthIndicator(store, props).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("size", "unavailable");
        assertThat(h.getDetails()).doesNotContainKey("fillPercent");
    }

    @Test
    void dlqIndicator_handlesProbeException() {
        DeadLetterStore store = mock(DeadLetterStore.class);
        when(store.size()).thenThrow(new RuntimeException("boom"));

        NotificationProperties props = new NotificationProperties();
        props.getDeadLetter().setMaxEntries(1_000);

        Health h = new DeadLetterStoreHealthIndicator(store, props).health();
        // A buggy SPI shouldn't crash the health endpoint — UNKNOWN
        // is the right signal: "we can't tell, here's why."
        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(h.getDetails()).containsKey("error");
    }

    // -----------------------------------------------------------------
    //  DeliveryEventStoreHealthIndicator
    // -----------------------------------------------------------------

    @Test
    void deliveryEventsIndicator_reportsUpWithSize() {
        DeliveryEventStore store = mock(DeliveryEventStore.class);
        when(store.size()).thenReturn(342);

        NotificationProperties props = new NotificationProperties();
        props.getDeliveryEvents().setMaxEntries(5_000);

        Health h = new DeliveryEventStoreHealthIndicator(store, props).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails())
                .containsEntry("size", 342)
                .containsEntry("maxEntries", 5_000);
        // Delivery events have no near-full alert — high arrival rate
        // is the expected steady state.
        assertThat(h.getDetails()).doesNotContainKey("fillPercent");
    }

    // -----------------------------------------------------------------
    //  IdempotencyStoreHealthIndicator
    // -----------------------------------------------------------------

    @Test
    void idempotencyIndicator_reportsImplClass() {
        IdempotencyStore store = mock(IdempotencyStore.class, "TestIdempotencyStore");

        Health h = new IdempotencyStoreHealthIndicator(store).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("enabled", true);
        // Mockito mock's class name will be a generated subclass.
        assertThat(h.getDetails()).containsKey("impl");
    }

    // -----------------------------------------------------------------
    //  RateLimiterHealthIndicator
    // -----------------------------------------------------------------

    @Test
    void rateLimiterIndicator_reportsImplClass() {
        RateLimiter rl = mock(RateLimiter.class);

        Health h = new RateLimiterHealthIndicator(rl).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("enabled", true);
        assertThat(h.getDetails()).containsKey("impl");
    }

    // Sanity sweep: no fields, no implementations matter beyond the
    // four indicators in this DD. (Kept as a placeholder to ensure
    // future maintainers don't accidentally rename the class set.)
    @Test
    void healthPackage_hasExpectedIndicators() {
        List<String> expected = List.of(
                "DeadLetterStoreHealthIndicator",
                "DeliveryEventStoreHealthIndicator",
                "IdempotencyStoreHealthIndicator",
                "RateLimiterHealthIndicator");
        assertThat(expected).hasSize(4);
        // Reference the classes so a rename forces this test to be updated.
        Optional<Class<?>> none = Optional.empty();
        assertThat(none).isEmpty();
        assertThat(DeadLetterStoreHealthIndicator.class.getSimpleName())
                .isEqualTo("DeadLetterStoreHealthIndicator");
        assertThat(DeliveryEventStoreHealthIndicator.class.getSimpleName())
                .isEqualTo("DeliveryEventStoreHealthIndicator");
        assertThat(IdempotencyStoreHealthIndicator.class.getSimpleName())
                .isEqualTo("IdempotencyStoreHealthIndicator");
        assertThat(RateLimiterHealthIndicator.class.getSimpleName())
                .isEqualTo("RateLimiterHealthIndicator");
    }
}
