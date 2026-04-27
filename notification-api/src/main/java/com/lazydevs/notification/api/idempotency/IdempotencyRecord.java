package com.lazydevs.notification.api.idempotency;

import com.lazydevs.notification.api.model.NotificationResponse;

import java.time.Instant;

/**
 * A single entry in the {@link IdempotencyStore}, recording the lifecycle
 * of one keyed dispatch.
 *
 * @param notificationId the {@code requestId} of the notification that
 *                       first claimed this key. Used in 409 responses so
 *                       a concurrent caller can correlate with the
 *                       in-flight send.
 * @param status         current state — see {@link IdempotencyStatus}.
 * @param response       the terminal {@link NotificationResponse} when
 *                       {@code status == COMPLETE}; {@code null} when
 *                       {@code status == IN_PROGRESS}.
 * @param recordedAt     wall-clock timestamp of the most recent state
 *                       transition. Used to drive TTL eviction.
 */
public record IdempotencyRecord(
        String notificationId,
        IdempotencyStatus status,
        NotificationResponse response,
        Instant recordedAt) {
}
