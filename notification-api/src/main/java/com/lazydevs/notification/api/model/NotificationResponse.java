package com.lazydevs.notification.api.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;

import java.time.Instant;

/**
 * Response for a notification request.
 *
 * @param requestId         request ID (same as request or auto-generated)
 * @param correlationId     correlation ID for cross-system tracking
 * @param tenantId          tenant ID
 * @param channel           channel used
 * @param provider          provider used (may be {@code null} if never dispatched)
 * @param status            current status of the notification
 * @param providerMessageId message ID from the provider (may be {@code null})
 * @param errorCode         error code (populated on failure)
 * @param errorMessage      error message (populated on failure)
 * @param receivedAt        timestamp when the request was received
 * @param processedAt       timestamp when the notification was processed
 * @param sentAt            timestamp when the notification was sent to the provider
 * @param idempotentReplay  internal flag — non-{@code null} {@link Boolean#TRUE}
 *                          when this response is being returned from the
 *                          idempotency cache rather than from a fresh
 *                          provider call. Marked {@link JsonIgnore} so it
 *                          is never serialised to the JSON response body —
 *                          the controller surfaces it instead via the
 *                          {@code X-Idempotent-Replay} HTTP header per DD-10.
 */
public record NotificationResponse(
        String requestId,
        String correlationId,
        String tenantId,
        Channel channel,
        String provider,
        NotificationStatus status,
        String providerMessageId,
        String errorCode,
        String errorMessage,
        Instant receivedAt,
        Instant processedAt,
        Instant sentAt,
        @JsonIgnore Boolean idempotentReplay) {

    // ======================================================================
    //  Minimal factories preserved from the original builder-based API.
    //  These match the legacy signatures so external callers are unaffected.
    // ======================================================================

    /**
     * Create a successful response using only request + provider + provider message id.
     * {@code receivedAt} is left {@code null}; {@code processedAt} and {@code sentAt}
     * are set to {@link Instant#now()}.
     */
    public static NotificationResponse success(NotificationRequest request, String provider, String providerMessageId) {
        Instant now = Instant.now();
        return new NotificationResponse(
                request.getRequestId(),
                request.getCorrelationId(),
                request.getTenantId(),
                request.getChannel(),
                provider,
                NotificationStatus.SENT,
                providerMessageId,
                null,
                null,
                null,
                now,
                now,
                null);
    }

    /**
     * Create a failed response. {@code processedAt} is set to {@link Instant#now()};
     * timestamps for {@code receivedAt}/{@code sentAt} are left {@code null}.
     */
    public static NotificationResponse failure(NotificationRequest request, String provider,
                                               String errorCode, String errorMessage) {
        return new NotificationResponse(
                request.getRequestId(),
                request.getCorrelationId(),
                request.getTenantId(),
                request.getChannel(),
                provider,
                NotificationStatus.FAILED,
                null,
                errorCode,
                errorMessage,
                null,
                Instant.now(),
                null,
                null);
    }

    /**
     * Create an accepted (queued) response. Only {@code receivedAt} is set.
     */
    public static NotificationResponse accepted(NotificationRequest request) {
        return new NotificationResponse(
                request.getRequestId(),
                request.getCorrelationId(),
                request.getTenantId(),
                request.getChannel(),
                null,
                NotificationStatus.ACCEPTED,
                null,
                null,
                null,
                Instant.now(),
                null,
                null,
                null);
    }

    /**
     * Create a rejected response.
     */
    public static NotificationResponse rejected(NotificationRequest request, String errorCode, String errorMessage) {
        return new NotificationResponse(
                request.getRequestId(),
                request.getCorrelationId(),
                request.getTenantId(),
                request.getChannel(),
                null,
                NotificationStatus.REJECTED,
                null,
                errorCode,
                errorMessage,
                null,
                Instant.now(),
                null,
                null);
    }

    // ======================================================================
    //  Extended factories for the core service. Include receivedAt so the
    //  caller can record the full lifecycle without resorting to a builder.
    // ======================================================================

    /**
     * Build a SENT response with explicit {@code receivedAt}/{@code sentAt}.
     * {@code processedAt} is stamped with {@link Instant#now()}.
     */
    public static NotificationResponse sent(NotificationRequest request, String provider,
                                            String providerMessageId,
                                            Instant receivedAt, Instant sentAt) {
        return new NotificationResponse(
                request.getRequestId(),
                request.getCorrelationId(),
                request.getTenantId(),
                request.getChannel(),
                provider,
                NotificationStatus.SENT,
                providerMessageId,
                null,
                null,
                receivedAt,
                Instant.now(),
                sentAt,
                null);
    }

    /**
     * Build a FAILED response with explicit {@code receivedAt}. {@code provider}
     * may be {@code null} if the failure occurred before provider resolution.
     */
    public static NotificationResponse failed(NotificationRequest request, String provider,
                                              String errorCode, String errorMessage,
                                              Instant receivedAt) {
        return new NotificationResponse(
                request.getRequestId(),
                request.getCorrelationId(),
                request.getTenantId(),
                request.getChannel(),
                provider,
                NotificationStatus.FAILED,
                null,
                errorCode,
                errorMessage,
                receivedAt,
                Instant.now(),
                null,
                null);
    }

    /**
     * Stamp an existing response as an idempotency cache replay (DD-10).
     *
     * <p>Used by {@code DefaultNotificationService} when returning a cached
     * response for a duplicate idempotency key — keeps the original
     * timestamps and provider message id intact (the caller is seeing
     * exactly what the original send produced) but flips the
     * {@link #idempotentReplay} flag so the controller can surface the
     * {@code X-Idempotent-Replay: true} header.
     *
     * <p>The flag is {@link JsonIgnore}d so the response body shape is
     * unchanged from a fresh send — the difference shows up only in the
     * HTTP header, exactly as DD-10 specifies.
     */
    public static NotificationResponse replayedFrom(NotificationResponse cached) {
        return new NotificationResponse(
                cached.requestId(),
                cached.correlationId(),
                cached.tenantId(),
                cached.channel(),
                cached.provider(),
                cached.status(),
                cached.providerMessageId(),
                cached.errorCode(),
                cached.errorMessage(),
                cached.receivedAt(),
                cached.processedAt(),
                cached.sentAt(),
                Boolean.TRUE);
    }
}
