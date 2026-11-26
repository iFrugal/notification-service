package com.lazydevs.notification.core.idempotency;

import com.lazydevs.notification.api.idempotency.IdempotencyReleasePolicy;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.core.config.NotificationProperties;

import java.util.Objects;

/**
 * The default {@link IdempotencyReleasePolicy}: releases a failed key as
 * {@code notification.idempotency.failure-policy} (or its deprecated alias
 * {@code retry-after-failure}) says, read on every call (DD-32).
 *
 * @since 1.3.0
 */
public final class FailurePolicyReleasePolicy implements IdempotencyReleasePolicy {

    private final NotificationProperties properties;

    /**
     * @param properties the notification properties, never {@code null}
     */
    public FailurePolicyReleasePolicy(NotificationProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public boolean releases(NotificationRequest request, NotificationResponse failed) {
        return properties.getIdempotency().resolvedFailurePolicy().releases(failed.failureType());
    }
}
