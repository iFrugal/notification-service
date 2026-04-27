package com.lazydevs.notification.api.exception;

import java.time.Duration;
import java.util.Objects;

/**
 * Thrown when the {@code (tenant, caller, channel)} rate-limit bucket is
 * exhausted (DD-12).
 *
 * <p>The REST controller advice maps this to <strong>HTTP 429 Too Many
 * Requests</strong> with a {@code Retry-After} header set to the same
 * rounded-up seconds value the message uses, so the body and the header
 * agree even for sub-second waits. Kafka path catches it like any other
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
    private final long retryAfterSeconds;

    public RateLimitExceededException(String tenantId, String callerId, String channel,
                                      Duration retryAfter) {
        // Build the message from a rounded-up seconds value rather than
        // Duration.toSeconds() — the latter truncates 200ms to "0s" which
        // would disagree with the controller-advice's Retry-After: 1.
        // Computed off a non-null local so the null-check below still
        // produces a clean NPE message rather than the opaque
        // "Cannot invoke toSeconds() because retryAfter is null".
        super("RATE_LIMIT_EXCEEDED",
                buildMessage(tenantId, callerId, channel, retryAfter),
                HTTP_TOO_MANY_REQUESTS);
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
        this.retryAfterSeconds = roundUpToSeconds(retryAfter);
    }

    /**
     * @return how long the caller should wait before retrying. The REST
     *         layer surfaces this as the {@code Retry-After} header value
     *         (rounded up to whole seconds via {@link #getRetryAfterSeconds()}).
     */
    public Duration getRetryAfter() {
        return retryAfter;
    }

    /**
     * @return the rounded-up whole-seconds form of {@link #getRetryAfter()},
     *         minimum 1. The same value is used in the message text and
     *         the {@code Retry-After} header so the two agree.
     */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static String buildMessage(String tenantId, String callerId, String channel,
                                       Duration retryAfter) {
        long seconds = retryAfter == null ? 0 : roundUpToSeconds(retryAfter);
        return "Rate limit exceeded for tenant=" + tenantId
                + ", caller=" + callerId
                + ", channel=" + channel
                + " (retry after " + seconds + "s)";
    }

    /**
     * RFC 7231 §7.1.3 only allows whole seconds in {@code Retry-After},
     * and a 200ms wait still wants the client to wait at least 1 second
     * to be sure the bucket has refilled — so we always round up and
     * never return zero.
     */
    private static long roundUpToSeconds(Duration d) {
        return Math.max(1L, (d.toMillis() + 999L) / 1000L);
    }
}
