package com.lazydevs.notification.core.metrics;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.model.FailureType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Typed wrapper around {@link MeterRegistry} for the DD-22 metrics
 * inventory. All meter names live in this class — the javadoc on
 * each method is the source of truth for the meter catalog.
 *
 * <p>Bean registers automatically when {@code MeterRegistry} is on
 * the classpath (i.e. {@code spring-boot-starter-actuator} is
 * present, which is the default for {@code notification-server}).
 * The send path injects this as
 * {@code Optional<NotificationMetrics>} so consumers without
 * actuator run with no metrics — see DD-22 §"Why
 * Optional&lt;NotificationMetrics&gt;".
 *
 * <p>Meter inventory (see DD-22 §"Meter inventory"):
 * <ul>
 *   <li>{@code notification.sends.total{channel, status}} — counter</li>
 *   <li>{@code notification.sends.duration{channel}} — timer</li>
 *   <li>{@code notification.retries.total{channel, attempt}} — counter</li>
 *   <li>{@code notification.rate-limit.denied.total{channel}} — counter</li>
 *   <li>{@code notification.idempotency.replay.total{tenant}} — counter</li>
 *   <li>{@code notification.dlq.added.total{channel, failureType}} — counter</li>
 *   <li>{@code notification.dlq.size} — gauge</li>
 *   <li>{@code notification.delivery-events.received.total{provider, status}} — counter</li>
 *   <li>{@code notification.delivery-events.size} — gauge</li>
 *   <li>{@code notification.webhook.signature.failed.total{provider}} — counter</li>
 * </ul>
 *
 * <p>Tag cardinality is deliberate: {@code tenant} only on
 * {@code idempotency.replay} where slicing-by-tenant answers a real
 * operational question (DD-22 §"Tag cardinality"). Other meters
 * stay bounded.
 */
@Slf4j
@Component
@ConditionalOnClass(MeterRegistry.class)
public class NotificationMetrics {

    private static final String M_SENDS_TOTAL = "notification.sends.total";
    private static final String M_SENDS_DURATION = "notification.sends.duration";
    private static final String M_RETRIES_TOTAL = "notification.retries.total";
    private static final String M_RATE_LIMIT_DENIED = "notification.rate-limit.denied.total";
    private static final String M_IDEMPOTENCY_REPLAY = "notification.idempotency.replay.total";
    private static final String M_DLQ_ADDED = "notification.dlq.added.total";
    private static final String M_DLQ_SIZE = "notification.dlq.size";
    private static final String M_DELIVERY_RECEIVED = "notification.delivery-events.received.total";
    private static final String M_DELIVERY_SIZE = "notification.delivery-events.size";
    private static final String M_WEBHOOK_SIG_FAILED = "notification.webhook.signature.failed.total";

    private final MeterRegistry registry;

    public NotificationMetrics(MeterRegistry registry,
                               Optional<DeadLetterStore> deadLetterStore,
                               Optional<DeliveryEventStore> deliveryEventStore) {
        this.registry = registry;

        // Gauges register at construction with a Supplier<Number> that
        // Micrometer polls on each registry-export tick. The size()
        // method returns -1 when the backend can't answer cheaply;
        // gauge value follows the SPI without translation.
        deadLetterStore.ifPresent(store ->
                registry.gauge(M_DLQ_SIZE, Tags.empty(), store, DeadLetterStore::size));
        deliveryEventStore.ifPresent(store ->
                registry.gauge(M_DELIVERY_SIZE, Tags.empty(), store, DeliveryEventStore::size));

        log.info("NotificationMetrics initialised (registry={}, dlq gauge={}, delivery gauge={})",
                registry.getClass().getSimpleName(),
                deadLetterStore.isPresent(),
                deliveryEventStore.isPresent());
    }

    /**
     * Record a completed send. Both the counter and the timer are
     * updated. Tags: {@code channel}, {@code status}.
     */
    public void recordSend(Channel channel, NotificationStatus status, Duration duration) {
        Tags tags = Tags.of(
                Tag.of("channel", channelTag(channel)),
                Tag.of("status", statusTag(status)));
        registry.counter(M_SENDS_TOTAL, tags).increment();
        Timer.builder(M_SENDS_DURATION)
                .tags(Tags.of(Tag.of("channel", channelTag(channel))))
                .register(registry)
                .record(duration);
    }

    /** Record a retry attempt (DD-13). Tags: {@code channel}, {@code attempt}. */
    public void recordRetry(Channel channel, int attempt) {
        registry.counter(M_RETRIES_TOTAL,
                "channel", channelTag(channel),
                "attempt", Integer.toString(attempt))
                .increment();
    }

    /** Record a rate-limit denial (DD-12). Tag: {@code channel}. */
    public void recordRateLimitDenied(Channel channel) {
        registry.counter(M_RATE_LIMIT_DENIED, "channel", channelTag(channel)).increment();
    }

    /**
     * Record an idempotency-replay short-circuit (DD-10). Tag:
     * {@code tenant} — the one meter where per-tenant slicing
     * answers a real operational question.
     */
    public void recordIdempotencyReplay(String tenantId) {
        registry.counter(M_IDEMPOTENCY_REPLAY,
                "tenant", tenantId == null ? "unknown" : tenantId).increment();
    }

    /** Record a DLQ addition (DD-13). Tags: {@code channel}, {@code failureType}. */
    public void recordDlqAdded(Channel channel, FailureType failureType) {
        registry.counter(M_DLQ_ADDED,
                "channel", channelTag(channel),
                "failureType", failureType == null ? "UNKNOWN" : failureType.name())
                .increment();
    }

    /**
     * Record a parsed delivery event ingested via webhook (DD-16).
     * Tags: {@code provider}, {@code status}.
     */
    public void recordDeliveryEventReceived(String providerName, DeliveryStatus status) {
        registry.counter(M_DELIVERY_RECEIVED,
                "provider", providerName == null ? "unknown" : providerName,
                "status", status == null ? "UNKNOWN" : status.name())
                .increment();
    }

    /**
     * Record a webhook signature-verification failure (DD-16). Tag:
     * {@code provider}.
     */
    public void recordWebhookSignatureFailed(String providerName) {
        registry.counter(M_WEBHOOK_SIG_FAILED,
                "provider", providerName == null ? "unknown" : providerName)
                .increment();
    }

    private static String channelTag(Channel channel) {
        return channel == null ? "unknown" : channel.name();
    }

    private static String statusTag(NotificationStatus status) {
        return status == null ? "UNKNOWN" : status.name();
    }
}
