package com.lazydevs.notification.core.metrics;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.model.FailureType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationMetrics}. Uses
 * {@link SimpleMeterRegistry} so the meter operations are observable
 * without standing up a Prometheus / OTLP exporter.
 *
 * <p>The test asserts that each method records to the right meter
 * name with the right tag set — DD-22's meter inventory is the spec.
 */
class NotificationMetricsTest {

    @Test
    void recordSend_incrementsCounterAndTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationMetrics metrics = new NotificationMetrics(registry,
                Optional.empty(), Optional.empty());

        metrics.recordSend(Channel.EMAIL, NotificationStatus.SENT, Duration.ofMillis(123));
        metrics.recordSend(Channel.EMAIL, NotificationStatus.SENT, Duration.ofMillis(456));
        metrics.recordSend(Channel.SMS, NotificationStatus.FAILED, Duration.ofMillis(789));

        // Counter tag combination = channel × status — two distinct
        // series.
        assertThat(registry.counter("notification.sends.total",
                "channel", "EMAIL", "status", "SENT").count())
                .isEqualTo(2.0);
        assertThat(registry.counter("notification.sends.total",
                "channel", "SMS", "status", "FAILED").count())
                .isEqualTo(1.0);
        // Timer tagged by channel only.
        assertThat(registry.timer("notification.sends.duration", "channel", "EMAIL").count())
                .isEqualTo(2L);
        assertThat(registry.timer("notification.sends.duration", "channel", "SMS").totalTime(
                java.util.concurrent.TimeUnit.MILLISECONDS))
                .isCloseTo(789.0, org.assertj.core.data.Offset.offset(50.0));
    }

    @Test
    void recordRetry_isTaggedByChannelAndAttempt() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationMetrics metrics = new NotificationMetrics(registry,
                Optional.empty(), Optional.empty());

        metrics.recordRetry(Channel.EMAIL, 2);
        metrics.recordRetry(Channel.EMAIL, 3);
        metrics.recordRetry(Channel.EMAIL, 3);

        assertThat(registry.counter("notification.retries.total",
                "channel", "EMAIL", "attempt", "2").count()).isEqualTo(1.0);
        assertThat(registry.counter("notification.retries.total",
                "channel", "EMAIL", "attempt", "3").count()).isEqualTo(2.0);
    }

    @Test
    void recordRateLimitDenied_isCounterByChannel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationMetrics metrics = new NotificationMetrics(registry,
                Optional.empty(), Optional.empty());

        metrics.recordRateLimitDenied(Channel.SMS);
        metrics.recordRateLimitDenied(Channel.SMS);

        assertThat(registry.counter("notification.rate-limit.denied.total",
                "channel", "SMS").count()).isEqualTo(2.0);
    }

    @Test
    void recordIdempotencyReplay_isTaggedByTenant() {
        // DD-22 §"Tag cardinality": tenant tag only on this meter.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationMetrics metrics = new NotificationMetrics(registry,
                Optional.empty(), Optional.empty());

        metrics.recordIdempotencyReplay("acme");
        metrics.recordIdempotencyReplay("acme");
        metrics.recordIdempotencyReplay("globex");

        assertThat(registry.counter("notification.idempotency.replay.total",
                "tenant", "acme").count()).isEqualTo(2.0);
        assertThat(registry.counter("notification.idempotency.replay.total",
                "tenant", "globex").count()).isEqualTo(1.0);
    }

    @Test
    void recordIdempotencyReplay_nullTenantMapsToUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationMetrics metrics = new NotificationMetrics(registry,
                Optional.empty(), Optional.empty());

        metrics.recordIdempotencyReplay(null);

        assertThat(registry.counter("notification.idempotency.replay.total",
                "tenant", "unknown").count()).isEqualTo(1.0);
    }

    @Test
    void recordDlqAdded_isTaggedByChannelAndFailureType() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationMetrics metrics = new NotificationMetrics(registry,
                Optional.empty(), Optional.empty());

        metrics.recordDlqAdded(Channel.EMAIL, FailureType.TRANSIENT);
        metrics.recordDlqAdded(Channel.EMAIL, FailureType.PERMANENT);

        assertThat(registry.counter("notification.dlq.added.total",
                "channel", "EMAIL", "failureType", "TRANSIENT").count()).isEqualTo(1.0);
        assertThat(registry.counter("notification.dlq.added.total",
                "channel", "EMAIL", "failureType", "PERMANENT").count()).isEqualTo(1.0);
    }

    @Test
    void recordDeliveryEventReceived_isTaggedByProviderAndStatus() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationMetrics metrics = new NotificationMetrics(registry,
                Optional.empty(), Optional.empty());

        metrics.recordDeliveryEventReceived("twilio", DeliveryStatus.DELIVERED);
        metrics.recordDeliveryEventReceived("twilio", DeliveryStatus.DELIVERED);
        metrics.recordDeliveryEventReceived("ses", DeliveryStatus.BOUNCED);

        assertThat(registry.counter("notification.delivery-events.received.total",
                "provider", "twilio", "status", "DELIVERED").count()).isEqualTo(2.0);
        assertThat(registry.counter("notification.delivery-events.received.total",
                "provider", "ses", "status", "BOUNCED").count()).isEqualTo(1.0);
    }

    @Test
    void recordWebhookSignatureFailed_isCounterByProvider() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationMetrics metrics = new NotificationMetrics(registry,
                Optional.empty(), Optional.empty());

        metrics.recordWebhookSignatureFailed("twilio");

        assertThat(registry.counter("notification.webhook.signature.failed.total",
                "provider", "twilio").count()).isEqualTo(1.0);
    }

    @Test
    void dlqSizeGauge_registersWhenStorePresent() {
        // Gauges register at construction with a Supplier pointing at
        // store.size(). Micrometer polls on each export tick — here we
        // read the gauge value directly to simulate that.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeadLetterStore dlq = mock(DeadLetterStore.class);
        when(dlq.size()).thenReturn(42);

        new NotificationMetrics(registry, Optional.of(dlq), Optional.empty());

        assertThat(registry.find("notification.dlq.size").gauge()).isNotNull();
        assertThat(registry.find("notification.dlq.size").gauge().value()).isEqualTo(42.0);
    }

    @Test
    void deliveryEventsSizeGauge_registersWhenStorePresent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeliveryEventStore store = mock(DeliveryEventStore.class);
        when(store.size()).thenReturn(123);

        new NotificationMetrics(registry, Optional.empty(), Optional.of(store));

        assertThat(registry.find("notification.delivery-events.size").gauge()).isNotNull();
        assertThat(registry.find("notification.delivery-events.size").gauge().value()).isEqualTo(123.0);
    }

    @Test
    void gauges_notRegisteredWhenStoreAbsent() {
        // With both optionals empty, no gauges register — counter-only
        // operation still works.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new NotificationMetrics(registry, Optional.empty(), Optional.empty());

        assertThat(registry.find("notification.dlq.size").gauge()).isNull();
        assertThat(registry.find("notification.delivery-events.size").gauge()).isNull();
    }
}
