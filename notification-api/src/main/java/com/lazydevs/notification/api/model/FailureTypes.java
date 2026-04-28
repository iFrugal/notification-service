package com.lazydevs.notification.api.model;

import java.io.IOException;

/**
 * Helpers for mapping native provider errors to {@link FailureType}
 * (DD-13 follow-up).
 *
 * <p>Each channel provider has its own SDK and its own error vocabulary,
 * but two patterns recur enough to be worth centralising:
 *
 * <ul>
 *   <li><strong>HTTP status code</strong> — most providers' SDKs surface
 *       a numeric status on their exception types. The mapping
 *       {@code 5xx | 408 | 425 | 429 → TRANSIENT}, other {@code 4xx →
 *       PERMANENT} is universal.</li>
 *   <li><strong>I/O / connectivity exceptions</strong> — connection
 *       timeouts, broken pipes, SSL handshake failures: always
 *       {@link FailureType#TRANSIENT}.</li>
 * </ul>
 *
 * <p>Provider-specific error vocabularies (Twilio's "21211 Invalid To",
 * AWS SES's {@code AccountSendingPausedException}, etc.) are still
 * mapped per-provider — those don't generalise. This helper covers the
 * shared ground.
 */
public final class FailureTypes {

    private FailureTypes() {
        // utility class
    }

    /**
     * Map an HTTP status code to a {@link FailureType}.
     *
     * <p>Treats as {@link FailureType#TRANSIENT}:
     * <ul>
     *   <li>{@code 408} (Request Timeout)</li>
     *   <li>{@code 425} (Too Early — RFC 8470)</li>
     *   <li>{@code 429} (Too Many Requests)</li>
     *   <li>any {@code 5xx} (server error)</li>
     * </ul>
     *
     * <p>Other {@code 4xx} → {@link FailureType#PERMANENT} (bad input,
     * auth failure, malformed payload — retrying won't help).
     *
     * <p>{@code 1xx}, {@code 2xx}, {@code 3xx} → {@link FailureType#UNKNOWN}.
     * A success status reaching this method is a programming error
     * (the caller should check {@code SendResult.success()} before
     * classifying); we don't throw, just defer to the predicate.
     */
    public static FailureType fromHttpStatus(int status) {
        if (status >= 500) {
            return FailureType.TRANSIENT;
        }
        if (status == 408 || status == 425 || status == 429) {
            return FailureType.TRANSIENT;
        }
        if (status >= 400) {
            return FailureType.PERMANENT;
        }
        return FailureType.UNKNOWN;
    }

    /**
     * Classify an exception by walking its cause chain looking for
     * I/O signals. {@link IOException} (and its subclasses
     * {@code SocketTimeoutException}, {@code ConnectException} etc.)
     * means the request never completed — almost always retry-worthy.
     *
     * <p>Returns {@link FailureType#UNKNOWN} when no I/O cause is
     * found, deferring to caller-side mapping or the default
     * {@code RetryPredicate}.
     */
    public static FailureType fromException(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof IOException) {
                return FailureType.TRANSIENT;
            }
            cur = cur.getCause();
        }
        return FailureType.UNKNOWN;
    }
}
