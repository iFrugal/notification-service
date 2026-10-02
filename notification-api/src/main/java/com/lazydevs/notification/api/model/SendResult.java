package com.lazydevs.notification.api.model;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/**
 * Result of sending a notification via a provider.
 * Returned by {@link com.lazydevs.notification.api.channel.NotificationProvider#send} method.
 *
 * @param success          whether the send was successful
 * @param messageId        provider-specific message ID (may be {@code null} on failure)
 * @param errorCode        error code (may be {@code null} on success)
 * @param errorMessage     error message (may be {@code null} on success)
 * @param failureType      classification of the failure for retry purposes
 *                         (DD-13). Always {@code null} on success.
 *                         {@link FailureType#UNKNOWN} when the provider
 *                         can't or hasn't classified the failure.
 * @param timestamp        timestamp of the result (never {@code null})
 * @param providerMetadata additional provider-specific metadata (may be {@code null})
 */
public record SendResult(
        boolean success,
        String messageId,
        String errorCode,
        String errorMessage,
        FailureType failureType,
        Instant timestamp,
        Map<String, Object> providerMetadata) {

    /**
     * Key in {@link #providerMetadata()} under which a provider passes the
     * delay it was asked to wait before the next attempt, typically taken from
     * an HTTP {@code Retry-After} header.
     *
     * <p>The value is an ISO-8601 duration string such as {@code "PT30S"}, or
     * a whole number of seconds: an integer, or a string of digits such as
     * {@code "30"}. A provider that receives an HTTP-date converts it to a
     * duration first. The retry executor waits at least this long before the
     * next attempt, but never longer than its configured {@code max-delay}.
     *
     * @see #retryAfter()
     * @since 1.1.2
     */
    public static final String RETRY_AFTER_METADATA_KEY = "retryAfter";

    /**
     * Compact constructor — ensures {@code timestamp} is never {@code null}.
     */
    public SendResult {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    /**
     * The provider's retry delay hint, read from
     * {@link #providerMetadata()} under {@link #RETRY_AFTER_METADATA_KEY}.
     *
     * @return the hint; empty when there is none, or when it is negative or
     *         not an ISO-8601 duration or a whole number of seconds
     * @since 1.1.2
     */
    public Optional<Duration> retryAfter() {
        Object value = providerMetadata == null ? null : providerMetadata.get(RETRY_AFTER_METADATA_KEY);
        Duration hint = switch (value) {
            case Duration d -> d;
            case Integer i -> Duration.ofSeconds(i);
            case Long l -> Duration.ofSeconds(l);
            case String s -> parseRetryAfter(s.trim());
            case null, default -> null;
        };
        return hint == null || hint.isNegative() ? Optional.empty() : Optional.of(hint);
    }

    private static Duration parseRetryAfter(String value) {
        try {
            if (!value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return Duration.ofSeconds(Long.parseLong(value));
            }
            return Duration.parse(value);
        } catch (NumberFormatException | DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Create a successful result.
     */
    public static SendResult success(String messageId) {
        return new SendResult(true, messageId, null, null, null, Instant.now(), null);
    }

    /**
     * Create a successful result with metadata.
     */
    public static SendResult success(String messageId, Map<String, Object> metadata) {
        return new SendResult(true, messageId, null, null, null, Instant.now(), metadata);
    }

    /**
     * Create a failed result with unknown classification — defers to the
     * configured {@code RetryPredicate}, which by default retries.
     * Backwards-compatible factory for providers that haven't been
     * upgraded to classify their failures.
     */
    public static SendResult failure(String errorCode, String errorMessage) {
        return new SendResult(false, null, errorCode, errorMessage,
                FailureType.UNKNOWN, Instant.now(), null);
    }

    /**
     * Create a failed result from an exception. Classified as
     * {@link FailureType#UNKNOWN} — the service defers to the configured
     * {@code RetryPredicate}.
     */
    public static SendResult failure(Exception e) {
        return new SendResult(false, null, e.getClass().getSimpleName(), e.getMessage(),
                FailureType.UNKNOWN, Instant.now(), null);
    }

    /**
     * Create an explicitly classified failure (DD-13). Providers with
     * signal about whether a failure is retry-able call this directly:
     * a 4xx that isn't 408/425/429 is {@link FailureType#PERMANENT};
     * a 5xx, timeout, or 429 is {@link FailureType#TRANSIENT}.
     */
    public static SendResult failure(String errorCode, String errorMessage, FailureType failureType) {
        return failure(errorCode, errorMessage, failureType, null);
    }

    /**
     * Create a classified failure that keeps the provider message id, for
     * providers that assign the id before they know the outcome (for example
     * a caller-chosen operation id). The id lets operators reconcile the
     * attempt with the provider and lets a retry reuse it.
     *
     * @param messageId provider message id, may be {@code null}
     * @since 1.1.1
     */
    public static SendResult failure(String errorCode, String errorMessage, FailureType failureType,
                                     String messageId) {
        return new SendResult(false, messageId, errorCode, errorMessage,
                failureType == null ? FailureType.UNKNOWN : failureType,
                Instant.now(), null);
    }
}
