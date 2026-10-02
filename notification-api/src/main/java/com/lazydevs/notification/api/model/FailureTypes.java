package com.lazydevs.notification.api.model;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import javax.net.ssl.SSLHandshakeException;

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

    /** Simple class name HTTP clients use for a connect timeout (Apache HttpClient 4 and 5). */
    private static final String CONNECT_TIMEOUT_EXCEPTION = "ConnectTimeoutException";

    /** Lower-cased message of the JDK's {@code SocketTimeoutException} on connect. */
    private static final String JDK_CONNECT_TIMEOUT_MESSAGE = "connect timed out";

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

    /**
     * Classify an exception thrown after the request may have been handed to
     * the provider, by walking its cause chain.
     *
     * <p>Unlike {@link #fromException(Throwable)}, which treats every
     * {@link IOException} as {@link FailureType#TRANSIENT}, this method only
     * calls a failure transient when the request cannot have left this
     * process:
     * <ul>
     *   <li>{@link ConnectException}, {@link UnknownHostException},
     *       {@link NoRouteToHostException},
     *       {@link HttpConnectTimeoutException} or
     *       {@link SSLHandshakeException} anywhere in the chain -
     *       {@link FailureType#TRANSIENT}: no connection, so nothing was
     *       sent. So is a connect timeout reported as a
     *       {@code SocketTimeoutException}: the JDK's own, reading
     *       "Connect timed out", and an HTTP client's
     *       {@code ConnectTimeoutException} (Apache HttpClient 5 makes it a
     *       {@code SocketTimeoutException}).</li>
     *   <li>any other {@link IOException} in the chain, including
     *       {@code SocketTimeoutException} and {@code HttpTimeoutException} -
     *       {@link FailureType#AMBIGUOUS}: the request may have reached the
     *       provider and been accepted before the response was lost.</li>
     *   <li>anything else, including {@code null} -
     *       {@link FailureType#UNKNOWN}.</li>
     * </ul>
     *
     * @param t the exception, may be {@code null}
     * @return the classification, never {@code null}
     * @since 1.2.0
     */
    public static FailureType fromExceptionAfterSubmit(Throwable t) {
        boolean io = false;
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (isConnectFailure(cur)) {
                return FailureType.TRANSIENT;
            }
            io |= cur instanceof IOException;
        }
        return io ? FailureType.AMBIGUOUS : FailureType.UNKNOWN;
    }

    private static boolean isConnectFailure(Throwable t) {
        return t instanceof ConnectException
                || t instanceof UnknownHostException
                || t instanceof NoRouteToHostException
                || t instanceof HttpConnectTimeoutException
                || t instanceof SSLHandshakeException
                || isConnectTimeout(t);
    }

    /**
     * A connect timeout that does not have a connect-specific JDK type.
     * HTTP clients are optional dependencies here, so their
     * {@code ConnectTimeoutException} is matched by simple name.
     */
    private static boolean isConnectTimeout(Throwable t) {
        if (CONNECT_TIMEOUT_EXCEPTION.equals(t.getClass().getSimpleName())) {
            return true;
        }
        String message = t.getMessage();
        return t instanceof SocketTimeoutException
                && message != null
                && message.toLowerCase(Locale.ROOT).contains(JDK_CONNECT_TIMEOUT_MESSAGE);
    }

    /**
     * Parse an HTTP {@code Retry-After} header value (RFC 9110, section
     * 10.2.3): either a whole number of seconds or an HTTP-date, measured
     * from {@code clock}.
     *
     * <p>Providers put the result on their failure with
     * {@link SendResult#withRetryAfter(Duration)}.
     *
     * @param header the header value, may be {@code null}
     * @param clock  the clock an HTTP-date is measured against
     * @return the delay; empty when the header is absent, blank or malformed,
     *         or the delay is not positive (a date in the past, or zero)
     * @since 1.2.0
     */
    public static Optional<Duration> parseRetryAfter(String header, Clock clock) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        String value = header.trim();
        Duration delay;
        try {
            delay = value.chars().allMatch(c -> c >= '0' && c <= '9')
                    ? Duration.ofSeconds(Long.parseLong(value))
                    : Duration.between(clock.instant(),
                            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (NumberFormatException | DateTimeException e) {
            return Optional.empty();
        }
        return delay.isPositive() ? Optional.of(delay) : Optional.empty();
    }
}
