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
 * @param timestamp        timestamp of the result (never {@code null})
 * @param providerMetadata additional provider-specific metadata (may be {@code null})
 */
public record SendResult(
        boolean success,
        String messageId,
        String errorCode,
        String errorMessage,
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
        return new SendResult(true, messageId, null, null, Instant.now(), null);
    }

    /**
     * Create a successful result with metadata.
     */
    public static SendResult success(String messageId, Map<String, Object> metadata) {
        return new SendResult(true, messageId, null, null, Instant.now(), metadata);
    }

    /**
     * Create a failed result.
     */
    public static SendResult failure(String errorCode, String errorMessage) {
        return new SendResult(false, null, errorCode, errorMessage, Instant.now(), null);
    }

    /**
     * Create a failed result from exception.
     */
    public static SendResult failure(Exception e) {
        return new SendResult(false, null, e.getClass().getSimpleName(), e.getMessage(), Instant.now(), null);
    }
}
