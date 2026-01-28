package com.lazydevs.notification.api.model;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response for a notification request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {

    /**
     * Request ID (same as request or auto-generated)
     */
    private String requestId;

    /**
     * Correlation ID for tracking
     */
    private String correlationId;

    /**
     * Tenant ID
     */
    private String tenantId;

    /**
     * Channel used
     */
    private Channel channel;

    /**
     * Provider used
     */
    private String provider;

    /**
     * Current status of the notification
     */
    private NotificationStatus status;

    /**
     * Message ID from the provider (if available)
     */
    private String providerMessageId;

    /**
     * Error code (if failed)
     */
    private String errorCode;

    /**
     * Error message (if failed)
     */
    private String errorMessage;

    /**
     * Timestamp when the request was received
     */
    private Instant receivedAt;

    /**
     * Timestamp when the notification was processed
     */
    private Instant processedAt;

    /**
     * Timestamp when the notification was sent
     */
    private Instant sentAt;

    /**
     * Create a successful response
     */
    public static NotificationResponse success(NotificationRequest request, String provider, String providerMessageId) {
        return NotificationResponse.builder()
                .requestId(request.getRequestId())
                .correlationId(request.getCorrelationId())
                .tenantId(request.getTenantId())
                .channel(request.getChannel())
                .provider(provider)
                .status(NotificationStatus.SENT)
                .providerMessageId(providerMessageId)
                .processedAt(Instant.now())
                .sentAt(Instant.now())
                .build();
    }

    /**
     * Create a failed response
     */
    public static NotificationResponse failure(NotificationRequest request, String provider,
                                                String errorCode, String errorMessage) {
        return NotificationResponse.builder()
                .requestId(request.getRequestId())
                .correlationId(request.getCorrelationId())
                .tenantId(request.getTenantId())
                .channel(request.getChannel())
                .provider(provider)
                .status(NotificationStatus.FAILED)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .processedAt(Instant.now())
                .build();
    }

    /**
     * Create an accepted (queued) response
     */
    public static NotificationResponse accepted(NotificationRequest request) {
        return NotificationResponse.builder()
                .requestId(request.getRequestId())
                .correlationId(request.getCorrelationId())
                .tenantId(request.getTenantId())
                .channel(request.getChannel())
                .status(NotificationStatus.ACCEPTED)
                .receivedAt(Instant.now())
                .build();
    }

    /**
     * Create a rejected response
     */
    public static NotificationResponse rejected(NotificationRequest request, String errorCode, String errorMessage) {
        return NotificationResponse.builder()
                .requestId(request.getRequestId())
                .correlationId(request.getCorrelationId())
                .tenantId(request.getTenantId())
                .channel(request.getChannel())
                .status(NotificationStatus.REJECTED)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .processedAt(Instant.now())
                .build();
    }
}
