package com.lazydevs.notification.api.idempotency;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
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
 *                       transition. Used to drive TTL eviction and the
 *                       in-progress timeout (DD-32).
 * @param fingerprint    SHA-256 hex fingerprint of the request that claimed
 *                       the key (DD-32), used to detect a key reused for a
 *                       different request. {@code null} means unknown: a
 *                       record written by 1.2.0 or earlier, or by a store
 *                       that does not keep fingerprints. Since 1.2.1.
 */
public record IdempotencyRecord(
        String notificationId,
        IdempotencyStatus status,
        NotificationResponse response,
        Instant recordedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String fingerprint) {

    /** Canonical constructor, the one JSON readers use. */
    @JsonCreator
    public IdempotencyRecord {
        // no normalisation; declared so Jackson picks this constructor
    }

    /**
     * The 1.2.0 constructor, without a fingerprint.
     */
    public IdempotencyRecord(String notificationId, IdempotencyStatus status,
                             NotificationResponse response, Instant recordedAt) {
        this(notificationId, status, response, recordedAt, null);
    }
}
