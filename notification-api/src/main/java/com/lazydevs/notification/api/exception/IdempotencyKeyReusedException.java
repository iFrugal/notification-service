package com.lazydevs.notification.api.exception;

import static java.net.HttpURLConnection.HTTP_CONFLICT;

/**
 * Thrown when an idempotency key is reused for a different request, with
 * {@code notification.idempotency.reject-key-reuse=true} (DD-32).
 *
 * <p>The request's fingerprint (channel, recipient, template, template data,
 * explicit provider and attachments) differs from the one stored with the
 * key. Replaying the first response would tell the caller a message it never
 * asked for was sent, and sending would break the key's promise, so the
 * request is refused.
 *
 * <p>The REST layer maps this to HTTP 409 Conflict with a body of
 * {@code {"notificationId":"...","status":"KEY_REUSED"}}, where
 * {@code notificationId} is the request id that first used the key.
 *
 * @since 1.3.0
 */
@SuppressWarnings("java:S110") // depth comes from the NotificationException tree shipped in 1.2.0
public class IdempotencyKeyReusedException extends NotificationException {

    /** Error code of this exception. */
    public static final String ERROR_CODE = "IDEMPOTENCY_KEY_REUSED";

    private final String existingNotificationId;

    public IdempotencyKeyReusedException(String existingNotificationId) {
        super(ERROR_CODE,
                "The idempotency key was already used for a different request (notificationId="
                        + existingNotificationId + ").",
                HTTP_CONFLICT);
        this.existingNotificationId = existingNotificationId;
    }

    /**
     * @return the {@code requestId} of the request that first used the key
     */
    public String getExistingNotificationId() {
        return existingNotificationId;
    }
}
