package com.lazydevs.notification.api.model;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.Priority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Notification request payload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequest {

    // ========== Identification ==========

    /**
     * Unique request ID (auto-generated if not provided)
     */
    private String requestId;

    /**
     * Correlation ID for cross-system tracking
     */
    private String correlationId;

    /**
     * Tenant ID (populated from X-Tenant-Id header if not provided)
     */
    private String tenantId;

    // ========== Routing ==========

    /**
     * Notification type (e.g., "ORDER_CONFIRMATION", "PASSWORD_RESET").
     * Maps to template: {channel}/{notificationType}.ftl
     */
    @NotBlank(message = "Notification type is required")
    private String notificationType;

    /**
     * Channel to use for sending the notification
     */
    @NotNull(message = "Channel is required")
    private Channel channel;

    /**
     * Specific provider to use (optional, uses default if not specified)
     */
    private String provider;

    // ========== Recipient ==========

    /**
     * Recipient details (channel-specific)
     */
    @NotNull(message = "Recipient is required")
    @Valid
    private Recipient recipient;

    // ========== Content ==========

    /**
     * Template data for rendering the notification content
     */
    private Map<String, Object> templateData;

    /**
     * Explicit template ID (overrides type-based template lookup)
     */
    private String templateId;

    // ========== Metadata ==========

    /**
     * Custom metadata for audit/tracking
     */
    private Map<String, String> metadata;

    /**
     * Notification priority
     */
    @Builder.Default
    private Priority priority = Priority.NORMAL;

    // ========== Scheduling ==========

    /**
     * Scheduled send time (null = send immediately)
     */
    private Instant scheduledAt;

    // ========== Attachments (Email only) ==========

    /**
     * Attachments for email notifications
     */
    private java.util.List<Attachment> attachments;

    /**
     * Email attachment.
     *
     * @param filename    attachment filename (as seen by the recipient)
     * @param contentType MIME content type, e.g. {@code application/pdf}
     * @param content     raw bytes of the attachment (mutually exclusive with {@code url})
     * @param url         URL to fetch the attachment from (alternative to {@code content})
     */
    public record Attachment(
            String filename,
            String contentType,
            byte[] content,
            String url) {
    }
}
