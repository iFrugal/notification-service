package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.util.MessageScrubber;

import java.time.Clock;
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
 * @since 1.3.0
 */
public record NotificationServiceExtensions(MessageScrubber messageScrubber, Clock clock,
                                            DeliveryEventStore deliveryEvents) {

    public NotificationServiceExtensions {
        Objects.requireNonNull(messageScrubber, "messageScrubber");
        Objects.requireNonNull(clock, "clock");
    }

    /**
     * The 1.3.0 defaults: {@link MessageScrubber#placeholders()}, the
     * system UTC clock and no delivery event store.
     *
     * @return the defaults
     */
    public static NotificationServiceExtensions defaults() {
        return new NotificationServiceExtensions(MessageScrubber.placeholders(), Clock.systemUTC(), null);
    }

    /**
     * @param messageScrubber scrubs persisted error messages
     * @param clock           time source
     * @return extensions without a delivery event store
     */
    public static NotificationServiceExtensions of(MessageScrubber messageScrubber, Clock clock) {
        return new NotificationServiceExtensions(messageScrubber, clock, null);
    }
}
