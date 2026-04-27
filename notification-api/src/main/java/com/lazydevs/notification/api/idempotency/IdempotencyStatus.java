package com.lazydevs.notification.api.idempotency;

/**
 * Lifecycle state of an entry in the {@link IdempotencyStore}.
 *
 * <ul>
 *   <li>{@link #IN_PROGRESS} — a notification with this key is currently
 *       being dispatched. Concurrent duplicate requests must short-circuit
 *       with HTTP 409 Conflict (see DD-10 §Semantics per state).</li>
 *   <li>{@link #COMPLETE} — dispatch finished. The associated
 *       {@link IdempotencyRecord#response()} carries the terminal
 *       {@link com.lazydevs.notification.api.model.NotificationResponse}
 *       to be replayed for duplicate sends within TTL.</li>
 * </ul>
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETE
}
