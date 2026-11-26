package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.guard.RecipientGuard;
import com.lazydevs.notification.api.idempotency.IdempotencyReleasePolicy;
import com.lazydevs.notification.api.util.MessageScrubber;
import com.lazydevs.notification.core.template.ChannelContentRenderer;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Optional collaborators of {@link DefaultNotificationService} added after
 * 1.2.0, grouped so its constructor does not grow with every release.
 *
 * @param messageScrubber scrubs error messages before they are persisted
 *                        (idempotency record, audit, status view)
 * @param clock           time source for the idempotency in-progress timeout
 * @param deliveryEvents  delivery event store joined into the status view
 *                        (DD-33), or {@code null} when delivery events are not kept
 * @param recipientGuard  decides the real recipients before dispatch (DD-34);
 *                        {@code null} means {@link RecipientGuard#allowAll()}
 * @param releasePolicy   decides whether a failed send releases its idempotency
 *                        key (DD-32); {@code null} means
 *                        {@code notification.idempotency.failure-policy}
 * @param renderers       per-channel content renderers, at most one per channel
 *                        (DD-35); {@code null} means none, every channel renders
 *                        with the template engine (since 1.4.0)
 * @since 1.3.0
 */
public record NotificationServiceExtensions(MessageScrubber messageScrubber, Clock clock,
                                            DeliveryEventStore deliveryEvents, RecipientGuard recipientGuard,
                                            IdempotencyReleasePolicy releasePolicy,
                                            List<ChannelContentRenderer> renderers) {

    public NotificationServiceExtensions {
        Objects.requireNonNull(messageScrubber, "messageScrubber");
        Objects.requireNonNull(clock, "clock");
        recipientGuard = recipientGuard == null ? RecipientGuard.allowAll() : recipientGuard;
        renderers = renderers == null ? List.of() : renderers.stream().filter(Objects::nonNull).toList();
    }

    /**
     * The 1.3.0 constructor: no per-channel content renderers.
     */
    public NotificationServiceExtensions(MessageScrubber messageScrubber, Clock clock,
                                         DeliveryEventStore deliveryEvents, RecipientGuard recipientGuard,
                                         IdempotencyReleasePolicy releasePolicy) {
        this(messageScrubber, clock, deliveryEvents, recipientGuard, releasePolicy, null);
    }

    /**
     * @param channelRenderers per-channel content renderers, at most one per channel
     * @return these extensions with {@code channelRenderers} in place of the current ones
     * @since 1.4.0
     */
    public NotificationServiceExtensions withRenderers(List<ChannelContentRenderer> channelRenderers) {
        return new NotificationServiceExtensions(messageScrubber, clock, deliveryEvents, recipientGuard,
                releasePolicy, channelRenderers);
    }

    /**
     * The 1.3.0 defaults: {@link MessageScrubber#placeholders()}, the
     * system UTC clock, no delivery event store, no recipient guard and the
     * configured failure policy.
     *
     * @return the defaults
     */
    public static NotificationServiceExtensions defaults() {
        return new NotificationServiceExtensions(MessageScrubber.placeholders(), Clock.systemUTC(), null, null, null);
    }

    /**
     * @param messageScrubber scrubs persisted error messages
     * @param clock           time source
     * @return extensions without a delivery event store, recipient guard or release policy
     */
    public static NotificationServiceExtensions of(MessageScrubber messageScrubber, Clock clock) {
        return new NotificationServiceExtensions(messageScrubber, clock, null, null, null);
    }
}
