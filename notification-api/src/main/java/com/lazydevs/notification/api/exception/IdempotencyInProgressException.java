package com.lazydevs.notification.api.exception;

import static java.net.HttpURLConnection.HTTP_CONFLICT;

/**
 * Thrown when an idempotent send is attempted while a prior send with the
 * same {@code (tenantId, callerId, idempotencyKey)} is still in flight.
 *
 * <p>The REST controller maps this to HTTP 409 Conflict with a body of
 * {@code {"notificationId":"...","status":"IN_PROGRESS"}} so the caller
 * can correlate with the in-flight send and decide whether to wait and
 * retry the same key (which will then hit the cached response) or move on.
 *
 * <p>See DD-10 §Semantics per state.
 */
public class IdempotencyInProgressException extends NotificationException {

    private final String inProgressNotificationId;

    public IdempotencyInProgressException(String inProgressNotificationId) {
        super("IDEMPOTENCY_IN_PROGRESS",
                "A request with the same idempotency key is already in progress (notificationId="
                        + inProgressNotificationId + ").",
                HTTP_CONFLICT);
        this.inProgressNotificationId = inProgressNotificationId;
    }

    /**
     * @return the {@code requestId} of the prior, still-in-flight send.
     */
    public String getInProgressNotificationId() {
        return inProgressNotificationId;
    }
}
