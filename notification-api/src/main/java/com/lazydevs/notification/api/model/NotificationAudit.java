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
@Builder(toBuilder = true)
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
     * Caller-supplied idempotency key of the request, or {@code null}
     * (DD-33). Together with {@link #tenantId} and {@link #callerId} it
     * finds the record through
     * {@code NotificationService.findByIdempotencyKey}.
     *
     * @since 1.3.0
     */
    private String idempotencyKey;

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
     * Masked recipient from
     * {@link com.lazydevs.notification.api.util.PiiMasking#mask(Recipient)},
     * e.g. {@code "to=j***@example.com cc=0 bcc=0"} or {@code "phone=+1***90"}.
     * Never the raw address or number.
     */
    private String recipientSummary;

    /**
     * Number of recipient addresses the send went to (to + cc + bcc for
     * email, one phone number for SMS and WhatsApp, the device tokens for
     * push), after the recipient guard; {@code null} when unknown, for
     * example a push topic (DD-33).
     *
     * @since 1.3.0
     */
    private Integer recipientCount;

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
     * Error message (if failed), after the configured {@code MessageScrubber}.
     */
    private String errorMessage;

    /**
     * Classification of the failure (DD-31), {@code null} unless failed.
     *
     * @since 1.3.0
     */
    private FailureType failureType;

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

    /**
     * When the send was recorded as {@code FAILED} or {@code REJECTED}
     * (DD-33), {@code null} otherwise.
     *
     * @since 1.3.0
     */
    private Instant failedAt;

    /**
     * When the record last changed (DD-33).
     *
     * @since 1.3.0
     */
    private Instant updatedAt;

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

    /**
     * Apply the outcome of a send to this record (DD-33): status, provider
     * and provider message id (when the outcome has them), error code and
     * message, failure type, processed and sent timestamps, {@code failedAt}
     * for {@code FAILED} and {@code REJECTED} (cleared otherwise) and
     * {@code updatedAt}. The rule every {@code NotificationAuditStore} follows.
     *
     * @param outcome the outcome
     * @param now     the time of the change
     * @since 1.3.0
     */
    public void applyOutcome(NotificationResponse outcome, Instant now) {
        status = outcome.status();
        if (outcome.provider() != null) {
            provider = outcome.provider();
        }
        if (outcome.providerMessageId() != null) {
            providerMessageId = outcome.providerMessageId();
        }
        errorCode = outcome.errorCode();
        errorMessage = outcome.errorMessage();
        failureType = outcome.failureType();
        processedAt = outcome.processedAt() != null ? outcome.processedAt() : now;
        if (outcome.sentAt() != null) {
            sentAt = outcome.sentAt();
        }
        failedAt = isFailure(outcome.status()) ? processedAt : null;
        updatedAt = now;
    }

    /**
     * @return whether {@code status} is a failed outcome ({@code FAILED} or {@code REJECTED})
     * @since 1.3.0
     */
    public static boolean isFailure(NotificationStatus status) {
        return status == NotificationStatus.FAILED || status == NotificationStatus.REJECTED;
    }

    /**
     * The 1.2.0 all-arguments constructor, without the fields added in 1.3.0
     * (DD-33), which are left {@code null}. Kept so code that called it keeps
     * compiling; prefer {@link #builder()}.
     *
     * @deprecated since 1.3.0, use {@link #builder()}
     */
    @Deprecated(since = "1.3.0")
    @SuppressWarnings("java:S107") // mirrors the 1.2.0 field list
    public NotificationAudit(String id, String requestId, String correlationId, String tenantId, String callerId,
                             String replayOf, String notificationType, Channel channel, String provider,
                             String recipientSummary, NotificationStatus status, String providerMessageId,
                             String errorCode, String errorMessage, Instant receivedAt, Instant processedAt,
                             Instant sentAt, Instant deliveredAt, Map<String, String> metadata, String templateId,
                             String requestPayload, String responsePayload) {
        this(id, requestId, correlationId, tenantId, callerId, null, replayOf, notificationType, channel, provider,
                recipientSummary, null, status, providerMessageId, errorCode, errorMessage, null, receivedAt,
                processedAt, sentAt, deliveredAt, null, null, metadata, templateId, requestPayload, responsePayload);
    }
}
