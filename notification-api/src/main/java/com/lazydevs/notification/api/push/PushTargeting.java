package com.lazydevs.notification.api.push;

/**
 * Error codes and metadata keys of user-addressed push (DD-37): a
 * {@link com.lazydevs.notification.api.model.PushRecipient} with a {@code userId}.
 *
 * @since 1.4.0
 */
public final class PushTargeting {

    /**
     * The user has no active registered device (none, or all stale). The response is
     * {@code FAILED} and {@code PERMANENT}; no provider is called and no dead letter is
     * recorded, because a retry or replay cannot succeed until the user registers a device.
     */
    public static final String NO_DEVICES = "NO_DEVICES";

    /**
     * The request addresses a user, but no {@link PushDeviceRegistry} is configured
     * ({@code notification.push.devices.enabled} is off and the application declares none).
     */
    public static final String USER_TARGETING_DISABLED = "PUSH_USER_TARGETING_DISABLED";

    /** The request names a {@code userId} together with another push target. */
    public static final String INVALID_USER_TARGET = "PUSH_INVALID_USER_TARGET";

    /**
     * {@link com.lazydevs.notification.api.model.NotificationRequest#getMetadata() Metadata}
     * key a push provider reads for the multi-token rule of one request: {@code any} (one
     * device reached is a success) or {@code all} (every device must be reached). The send
     * pipeline sets it for a user target from
     * {@code notification.push.user-targets.multi-token-policy} unless the caller set it.
     */
    public static final String MULTI_TOKEN_POLICY_METADATA = "push.multi-token-policy";

    private PushTargeting() {
    }
}
