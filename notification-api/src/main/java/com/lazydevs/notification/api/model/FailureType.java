package com.lazydevs.notification.api.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Classification of a {@link SendResult} failure for retry decisions
 * (DD-13).
 *
 * <p>Providers populate this on the failure path when they have signal:
 * a Twilio 429 is {@link #TRANSIENT}, a Twilio "Invalid To Number" 4xx
 * is {@link #PERMANENT}, an unrecognised exception is {@link #UNKNOWN}.
 * The default {@code RetryPredicate} retries TRANSIENT and UNKNOWN,
 * skips PERMANENT and {@link #AMBIGUOUS}.
 *
 * <p>Constants are only ever appended. A reader that enables
 * {@code READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE} (the Redis and JDBC
 * stores since 1.1.2) reads a constant it does not know as {@link #UNKNOWN}.
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
     *
     * <p>Also the value a reader that enables
     * {@code READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE} uses for a
     * constant added by a later version (since 1.1.2).
     */
    @JsonEnumDefaultValue
    UNKNOWN,

    /**
     * The request may have reached the provider, so the message may already
     * be on its way: the connection was established and the request sent,
     * but no answer came back. Examples: a read timeout after the request
     * body was written; a connection reset while waiting for the response.
     *
     * <p>Retrying risks a duplicate message, so the default
     * {@code RetryPredicate} does not retry it; the failure goes to the
     * dead-letter store when one is configured, where an operator can
     * reconcile it with the provider before replaying. A custom predicate
     * may retry it, for example for a provider that deduplicates on an
     * idempotency key. A predicate written as {@code type != PERMANENT}
     * retries it.
     *
     * <p>Readers from 1.1.2 that predate this constant read it as
     * {@link #UNKNOWN}.
     *
     * @since 1.2.0
     */
    AMBIGUOUS
}
