package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.guard.RecipientGuard;
import com.lazydevs.notification.api.idempotency.IdempotencyReleasePolicy;
import com.lazydevs.notification.api.util.MessageScrubber;
import com.lazydevs.notification.core.push.PushTargetExpansion;
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
 *                        with the template engine (since 1.2.2)
 * @param pushTargets     resolves push recipients that address a user into the
 *                        user's device tokens (DD-37); {@code null} means
 *                        {@link PushTargetExpansion#disabled()} (since 1.2.3)
 * @since 1.2.1
 */
public record NotificationServiceExtensions(MessageScrubber messageScrubber, Clock clock,
                                            DeliveryEventStore deliveryEvents, RecipientGuard recipientGuard,
                                            IdempotencyReleasePolicy releasePolicy,
                                            List<ChannelContentRenderer> renderers,
                                            PushTargetExpansion pushTargets) {

    public NotificationServiceExtensions {
        Objects.requireNonNull(messageScrubber, "messageScrubber");
        Objects.requireNonNull(clock, "clock");
        recipientGuard = recipientGuard == null ? RecipientGuard.allowAll() : recipientGuard;
        renderers = renderers == null ? List.of() : renderers.stream().filter(Objects::nonNull).toList();
        pushTargets = pushTargets == null ? PushTargetExpansion.disabled() : pushTargets;
    }

    /**
     * The 1.2.2 constructor: no push user targeting.
     *
     * @since 1.2.2
     */
    public NotificationServiceExtensions(MessageScrubber messageScrubber, Clock clock,
                                         DeliveryEventStore deliveryEvents, RecipientGuard recipientGuard,
                                         IdempotencyReleasePolicy releasePolicy,
                                         List<ChannelContentRenderer> renderers) {
        this(messageScrubber, clock, deliveryEvents, recipientGuard, releasePolicy, renderers, null);
    }

    /**
     * The 1.2.1 constructor: no per-channel content renderers.
     */
    public NotificationServiceExtensions(MessageScrubber messageScrubber, Clock clock,
                                         DeliveryEventStore deliveryEvents, RecipientGuard recipientGuard,
                                         IdempotencyReleasePolicy releasePolicy) {
        this(messageScrubber, clock, deliveryEvents, recipientGuard, releasePolicy, null);
    }

    /**
     * @param channelRenderers per-channel content renderers, at most one per channel
     * @return these extensions with {@code channelRenderers} in place of the current ones
     * @since 1.2.2
     */
    public NotificationServiceExtensions withRenderers(List<ChannelContentRenderer> channelRenderers) {
        return new NotificationServiceExtensions(messageScrubber, clock, deliveryEvents, recipientGuard,
                releasePolicy, channelRenderers, pushTargets);
    }

    /**
     * @param expansion resolves push user targets; {@code null} disables user targeting
     * @return these extensions with {@code expansion} in place of the current one
     * @since 1.2.3
     */
    public NotificationServiceExtensions withPushTargets(PushTargetExpansion expansion) {
        return new NotificationServiceExtensions(messageScrubber, clock, deliveryEvents, recipientGuard,
                releasePolicy, renderers, expansion);
    }

    /**
     * The 1.2.1 defaults: {@link MessageScrubber#placeholders()}, the
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
