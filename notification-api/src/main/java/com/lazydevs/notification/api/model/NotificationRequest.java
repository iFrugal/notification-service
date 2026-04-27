package com.lazydevs.notification.api.model;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.Priority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

    /**
     * Optional caller-supplied idempotency key. If present, duplicate
     * requests within the configured TTL are deduplicated against an
     * {@link com.lazydevs.notification.api.idempotency.IdempotencyStore}.
     * Scoped per {@code (tenantId, callerId, idempotencyKey)} — see DD-10.
     * Maximum 255 characters.
     */
    @Size(max = 255, message = "idempotencyKey must be at most 255 characters")
    private String idempotencyKey;

    /**
     * Optional identifier of the upstream service that initiated this
     * request. Populated from the {@code X-Service-Id} HTTP header by the
     * REST filter (see DD-11). If both the header and an explicit body
     * value are present, the body wins — same precedence rule
     * {@code tenantId} uses (DD-03 §request-precedence).
     *
     * <p>Used by:
     * <ul>
     *   <li>The idempotency store as part of the dedup tuple
     *       {@code (tenantId, callerId, idempotencyKey)} (DD-10).</li>
     *   <li>The audit record for "who sent it?" traceability.</li>
     *   <li>The optional caller registry for admission control / observability.</li>
     * </ul>
     *
     * <p>Maximum 128 characters; ASCII service-name conventions apply but
     * are not enforced here.
     */
    @Size(max = 128, message = "callerId must be at most 128 characters")
    private String callerId;

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
