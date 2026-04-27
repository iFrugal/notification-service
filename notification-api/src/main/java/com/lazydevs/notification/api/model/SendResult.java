package com.lazydevs.notification.api.model;

import java.time.Instant;
import java.util.Map;

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
     * Compact constructor — ensures {@code timestamp} is never {@code null}.
     */
    public SendResult {
        if (timestamp == null) {
            timestamp = Instant.now();
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
        return new SendResult(false, null, errorCode, errorMessage,
                failureType == null ? FailureType.UNKNOWN : failureType,
                Instant.now(), null);
    }
}
