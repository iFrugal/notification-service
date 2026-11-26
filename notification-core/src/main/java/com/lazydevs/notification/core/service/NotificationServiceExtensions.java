package com.lazydevs.notification.core.service;

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
 * @since 1.3.0
 */
public record NotificationServiceExtensions(MessageScrubber messageScrubber, Clock clock) {

    public NotificationServiceExtensions {
        Objects.requireNonNull(messageScrubber, "messageScrubber");
        Objects.requireNonNull(clock, "clock");
    }

    /**
     * The 1.3.0 defaults: {@link MessageScrubber#placeholders()} and the
     * system UTC clock.
     *
     * @return the defaults
     */
    public static NotificationServiceExtensions defaults() {
        return new NotificationServiceExtensions(MessageScrubber.placeholders(), Clock.systemUTC());
    }
}
