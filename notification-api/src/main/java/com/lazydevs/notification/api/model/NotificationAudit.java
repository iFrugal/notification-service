package com.lazydevs.notification.api.model;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Audit record for a notification.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationAudit {

    /**
     * Unique audit record ID
     */
    private String id;

    /**
     * Original request ID
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
     * Calling-service identifier (DD-11). Populated from the
     * {@code X-Service-Id} header or request body. May be {@code null} when
     * the caller did not identify itself.
     */
    private String callerId;

    /**
     * Reference back to the original request id when this audit row
     * captures a DLQ replay (DD-15). {@code null} on fresh sends.
     * Mirrors {@link NotificationRequest#getReplayOf()} so log search
     * for "everything that happened to this customer" can join the
     * original and the replay records.
     */
    private String replayOf;

    // ========== Request Details ==========

    /**
     * Notification type
     */
    private String notificationType;

    /**
     * Channel used
     */
    private Channel channel;

    /**
     * Provider used
     */
    private String provider;

    /**
     * Masked recipient (e.g., "j***@example.com", "+1***890")
     */
    private String recipientSummary;

    // ========== Status Tracking ==========

    /**
     * Current status
     */
    private NotificationStatus status;

    /**
     * Provider message ID
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

    // ========== Timestamps ==========

    /**
     * When the request was received
     */
    private Instant receivedAt;

    /**
     * When processing started
     */
    private Instant processedAt;

    /**
     * When the notification was sent
     */
    private Instant sentAt;

    /**
     * When delivery was confirmed (if supported)
     */
    private Instant deliveredAt;

    // ========== Metadata ==========

    /**
     * Custom metadata from the request
     */
    private Map<String, String> metadata;

    /**
     * Template ID used
     */
    private String templateId;

    /**
     * Full request payload (if audit.store-request-payload=true)
     */
    private String requestPayload;

    /**
     * Provider response (if audit.store-response-payload=true)
     */
    private String responsePayload;
}
