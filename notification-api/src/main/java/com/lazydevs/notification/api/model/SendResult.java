package com.lazydevs.notification.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Result of sending a notification via a provider.
 * Returned by NotificationProvider.send() method.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendResult {

    /**
     * Whether the send was successful
     */
    private boolean success;

    /**
     * Provider-specific message ID
     */
    private String messageId;

    /**
     * Error code (if failed)
     */
    private String errorCode;

    /**
     * Error message (if failed)
     */
    private String errorMessage;

    /**
     * Timestamp of the result
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * Additional provider-specific metadata
     */
    private Map<String, Object> providerMetadata;

    /**
     * Create a successful result
     */
    public static SendResult success(String messageId) {
        return SendResult.builder()
                .success(true)
                .messageId(messageId)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Create a successful result with metadata
     */
    public static SendResult success(String messageId, Map<String, Object> metadata) {
        return SendResult.builder()
                .success(true)
                .messageId(messageId)
                .providerMetadata(metadata)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Create a failed result
     */
    public static SendResult failure(String errorCode, String errorMessage) {
        return SendResult.builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Create a failed result from exception
     */
    public static SendResult failure(Exception e) {
        return SendResult.builder()
                .success(false)
                .errorCode(e.getClass().getSimpleName())
                .errorMessage(e.getMessage())
                .timestamp(Instant.now())
                .build();
    }
}
