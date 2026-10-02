package com.lazydevs.notification.api.delivery;

/**
 * Shared vocabulary for {@link DeliveryEvent}s that providers publish
 * themselves (DD-25), so listeners can recognise them without knowing the
 * provider.
 *
 * <p>An invalid target, such as a device token the push service no longer
 * knows, is published as {@link DeliveryStatus#BOUNCED} with
 * {@link DeliveryEvent#reason()} set to {@link #REASON_INVALID_TARGET} and
 * these attributes:
 * <ul>
 *   <li>{@link #ATTR_FAILURE} - the provider's failure name, for example
 *       {@code UNREGISTERED}</li>
 *   <li>{@link #ATTR_ERROR_CODE} - the {@code errorCode} of the failed send result</li>
 *   <li>{@link #ATTR_TARGET_TYPE} - {@link #TARGET_TYPE_TOKEN} or {@link #TARGET_TYPE_FID}</li>
 *   <li>{@link #ATTR_TOKEN_HASH} - a hash of the target, never the target itself,
 *       for example {@code sha256:0123456789abcdef}</li>
 * </ul>
 *
 * @since 1.2.0
 */
public final class DeliveryEvents {

    /** {@link DeliveryEvent#reason()} of a bounce caused by a target the provider rejects. */
    public static final String REASON_INVALID_TARGET = "INVALID_TARGET";

    /** Attribute: the provider's name for the failure. */
    public static final String ATTR_FAILURE = "failure";

    /** Attribute: the error code of the failed send result. */
    public static final String ATTR_ERROR_CODE = "errorCode";

    /** Attribute: what kind of target was rejected. */
    public static final String ATTR_TARGET_TYPE = "targetType";

    /** Attribute: hash of the rejected target. */
    public static final String ATTR_TOKEN_HASH = "tokenHash";

    /** {@link #ATTR_TARGET_TYPE} value for a device registration token. */
    public static final String TARGET_TYPE_TOKEN = "token";

    /** {@link #ATTR_TARGET_TYPE} value for a Firebase installation id. */
    public static final String TARGET_TYPE_FID = "fid";

    private DeliveryEvents() {
    }
}
