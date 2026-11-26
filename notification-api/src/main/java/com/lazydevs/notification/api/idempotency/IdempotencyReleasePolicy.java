package com.lazydevs.notification.api.idempotency;

import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;

/**
 * Decides whether a failed send gives its idempotency key back, so a retry
 * under the same key is dispatched again (DD-32).
 *
 * <p>{@code DefaultNotificationService} asks the policy once per failed
 * ({@code FAILED} or {@code REJECTED}) outcome under an idempotency key: right
 * after the send, and again when a later request under the key finds a failed
 * record that is still there. A key the policy keeps is answered with the
 * stored failure (replayed) under {@code failure-policy=RELEASE_TRANSIENT},
 * otherwise with HTTP 409 until the TTL. Successful outcomes are never offered
 * to the policy, and neither is a record settled by
 * {@code notification.idempotency.in-progress-timeout}: that one may have been
 * delivered and is never released, whatever the policy says.
 *
 * <p>When {@code notification.rate-limit.refund-unsent} is on, a key this
 * policy releases also gets its rate-limit token back, unless the failure is
 * {@code AMBIGUOUS} or {@code UNKNOWN} (the provider may have accepted it).
 *
 * <p>The library's default implements
 * {@code notification.idempotency.failure-policy}: {@code RELEASE_ALL}
 * releases every failure, {@code KEEP} none, {@code RELEASE_TRANSIENT} only
 * {@code TRANSIENT} ones. Declare a bean of this type to replace it, for
 * example to also release a failure whose provider error code says the
 * sender's credential was rejected, which nothing delivered. Implementations
 * must be thread-safe and must not throw.
 *
 * @since 1.2.1
 */
@FunctionalInterface
public interface IdempotencyReleasePolicy {

    /**
     * @param request the request under the key: the one that failed, or the
     *                retry that found an earlier failure still stored
     * @param failed  the failed outcome; its {@code failureType} may be
     *                {@code null} (written by 1.2.0 or not classified) and
     *                its error message may already have been removed
     * @return {@code true} to release the key for a new send
     */
    boolean releases(NotificationRequest request, NotificationResponse failed);
}
