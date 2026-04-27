package com.lazydevs.notification.api.exception;

import java.time.Duration;
import java.util.Objects;

/**
 * Thrown when the {@code (tenant, caller, channel)} rate-limit bucket is
 * exhausted (DD-12).
 *
 * <p>The REST controller advice maps this to <strong>HTTP 429 Too Many
 * Requests</strong> with a {@code Retry-After} header set to
 * {@code retryAfter().toSeconds()}. Kafka path catches it like any other
 * exception, logs at WARN, and lets the offset commit — at-least-once
 * semantics with rate limiting would amplify the very pressure we're
 * trying to relieve.
 *
 * <p>HTTP 429 is the standard status code (RFC 6585); we don't reuse the
 * generic {@link NotificationException#getHttpStatus()} machinery because
 * 429 isn't in {@link java.net.HttpURLConnection}.
 */
public class RateLimitExceededException extends NotificationException {

    /** HTTP 429 — not present in {@link java.net.HttpURLConnection}. */
    public static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final Duration retryAfter;

    public RateLimitExceededException(String tenantId, String callerId, String channel,
                                      Duration retryAfter) {
        super("RATE_LIMIT_EXCEEDED",
                "Rate limit exceeded for tenant=" + tenantId
                        + ", caller=" + callerId
                        + ", channel=" + channel
                        + " (retry after " + retryAfter.toSeconds() + "s)",
                HTTP_TOO_MANY_REQUESTS);
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    /**
     * @return how long the caller should wait before retrying. The REST
     *         layer surfaces this as the {@code Retry-After} header value
     *         (rounded up to whole seconds).
     */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
