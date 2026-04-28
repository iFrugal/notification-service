package com.lazydevs.notification.api.model;

/**
 * Classification of a {@link SendResult} failure for retry decisions
 * (DD-13).
 *
 * <p>Providers populate this on the failure path when they have signal:
 * a Twilio 429 is {@link #TRANSIENT}, a Twilio "Invalid To Number" 4xx
 * is {@link #PERMANENT}, an unrecognised exception is {@link #UNKNOWN}.
 * The default {@code RetryPredicate} retries TRANSIENT and UNKNOWN,
 * skips PERMANENT.
 */
public enum FailureType {

    /**
     * Worth retrying — provider is temporarily refusing or unreachable.
     * Examples: HTTP 5xx, 429, 408, 425; I/O timeouts; broken pipes.
     */
    TRANSIENT,

    /**
     * Will not succeed on retry — bad input or permanent rejection.
     * Examples: HTTP 4xx (except retry-friendly ones above); auth
     * failures; "Invalid To Number"; malformed payload.
     */
    PERMANENT,

    /**
     * The provider didn't classify this failure. The service defers
     * to the configured {@code RetryPredicate} — by default these are
     * treated as TRANSIENT (best-effort retry). Channel implementations
     * upgrading to DD-13 classification can keep returning UNKNOWN
     * until they're confident about which 4xxs are permanent.
     */
    UNKNOWN
}
