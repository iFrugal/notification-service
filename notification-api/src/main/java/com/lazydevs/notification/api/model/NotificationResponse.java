package com.lazydevs.notification.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Response for a notification request.
 *
 * @param requestId         request ID (same as request or auto-generated)
 * @param correlationId     correlation ID for cross-system tracking
 * @param tenantId          tenant ID
 * @param callerId          calling-service identifier echoed back from
 *                          the request — populated from {@code X-Service-Id}
 *                          header or request body. May be {@code null} when
 *                          the caller didn't identify itself. See DD-11.
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
 * @param failureType       classification of a failure (DD-13): whether a
 *                          retry is safe ({@link FailureType#TRANSIENT}),
 *                          pointless ({@link FailureType#PERMANENT}) or may
 *                          duplicate a message that was already accepted
 *                          ({@link FailureType#AMBIGUOUS}). {@code null} on
 *                          success and on responses written by 1.2.0 or
 *                          earlier; omitted from JSON when {@code null}.
 *                          Since 1.3.0 (DD-31).
 * @param providerMetadata  provider-specific details of the send, from
 *                          {@link SendResult#providerMetadata()}, for example
 *                          an ACS {@code acsStatus} or a retry hint.
 *                          {@code null} when the provider gave none; omitted
 *                          from JSON when {@code null}. Since 1.3.0 (DD-31).
 * @param retryAfter        how long the provider asked to wait before the next
 *                          attempt, from {@link SendResult#retryAfter()} (for
 *                          example an HTTP 429 {@code Retry-After}) on a failed
 *                          send. {@code null} when the provider gave no hint;
 *                          omitted from JSON when {@code null}. Since 1.3.0 (DD-31).
 */
public record NotificationResponse(
        String requestId,
        String correlationId,
        String tenantId,
        String callerId,
        Channel channel,
        String provider,
        NotificationStatus status,
        String providerMessageId,
        String errorCode,
        String errorMessage,
        Instant receivedAt,
        Instant processedAt,
        Instant sentAt,
        @JsonIgnore Boolean idempotentReplay,
        @JsonInclude(JsonInclude.Include.NON_NULL) FailureType failureType,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> providerMetadata,
        // ISO-8601 ("PT30S") whatever the mapper's duration settings, so REST and stores agree
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonFormat(shape = JsonFormat.Shape.STRING) Duration retryAfter) {

    /**
     * Canonical constructor. Copies {@code providerMetadata} into an
     * unmodifiable map so the response stays a value; a negative or zero
     * {@code retryAfter} is no hint ({@code null}).
     */
    @JsonCreator
    public NotificationResponse {
        providerMetadata = providerMetadata == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(providerMetadata));
        retryAfter = retryAfter == null || retryAfter.isNegative() || retryAfter.isZero() ? null : retryAfter;
    }

    /**
     * The 1.2.0 constructor, without {@link #failureType()},
     * {@link #providerMetadata()} and {@link #retryAfter()} (all {@code null}).
     * Kept so code that builds responses directly keeps compiling.
     */
    @SuppressWarnings("java:S107") // mirrors the record components
    public NotificationResponse(String requestId, String correlationId, String tenantId, String callerId,
                                Channel channel, String provider, NotificationStatus status,
                                String providerMessageId, String errorCode, String errorMessage,
                                Instant receivedAt, Instant processedAt, Instant sentAt,
                                Boolean idempotentReplay) {
        this(requestId, correlationId, tenantId, callerId, channel, provider, status, providerMessageId,
                errorCode, errorMessage, receivedAt, processedAt, sentAt, idempotentReplay, null, null, null);
    }

    /**
     * A copy of this response with the given failure classification.
     *
     * @param type the classification, may be {@code null}
     * @return the copy; this response is not changed
     * @since 1.3.0
     */
    public NotificationResponse withFailureType(FailureType type) {
        return new NotificationResponse(requestId, correlationId, tenantId, callerId, channel, provider, status,
                providerMessageId, errorCode, errorMessage, receivedAt, processedAt, sentAt, idempotentReplay,
                type, providerMetadata, retryAfter);
    }

    /**
     * A copy of this response with the given provider metadata. An empty
     * map is stored as {@code null}, so it is omitted from JSON.
     *
     * @param metadata the provider metadata, may be {@code null}
     * @return the copy; this response is not changed
     * @since 1.3.0
     */
    public NotificationResponse withProviderMetadata(Map<String, Object> metadata) {
        return new NotificationResponse(requestId, correlationId, tenantId, callerId, channel, provider, status,
                providerMessageId, errorCode, errorMessage, receivedAt, processedAt, sentAt, idempotentReplay,
                failureType, metadata == null || metadata.isEmpty() ? null : metadata, retryAfter);
    }

    /**
     * A copy of this response with another error message, for example one
     * scrubbed before it is persisted.
     *
     * @param message the error message, may be {@code null}
     * @return the copy; this response is not changed
     * @since 1.3.0
     */
    public NotificationResponse withErrorMessage(String message) {
        return new NotificationResponse(requestId, correlationId, tenantId, callerId, channel, provider, status,
                providerMessageId, errorCode, message, receivedAt, processedAt, sentAt, idempotentReplay,
                failureType, providerMetadata, retryAfter);
    }

    /**
     * A copy of this response with the provider's retry hint.
     *
     * @param delay the delay, may be {@code null}; zero or negative is no hint
     * @return the copy; this response is not changed
     * @since 1.3.0
     */
    public NotificationResponse withRetryAfter(Duration delay) {
        return new NotificationResponse(requestId, correlationId, tenantId, callerId, channel, provider, status,
                providerMessageId, errorCode, errorMessage, receivedAt, processedAt, sentAt, idempotentReplay,
                failureType, providerMetadata, delay);
    }

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
                request.getCallerId(),
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
                request.getCallerId(),
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
                request.getCallerId(),
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
                request.getCallerId(),
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
                request.getCallerId(),
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
        return failed(request, provider, null, errorCode, errorMessage, receivedAt);
    }

    /**
     * Build a FAILED response that keeps the provider message id, for a send
     * the provider had already identified (for example an ACS operation id)
     * before it failed. {@code provider} and {@code providerMessageId} may be
     * {@code null}.
     *
     * @since 1.1.1
     */
    public static NotificationResponse failed(NotificationRequest request, String provider,
                                              String providerMessageId,
                                              String errorCode, String errorMessage,
                                              Instant receivedAt) {
        return new NotificationResponse(
                request.getRequestId(),
                request.getCorrelationId(),
                request.getTenantId(),
                request.getCallerId(),
                request.getChannel(),
                provider,
                NotificationStatus.FAILED,
                providerMessageId,
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
                cached.callerId(),
                cached.channel(),
                cached.provider(),
                cached.status(),
                cached.providerMessageId(),
                cached.errorCode(),
                cached.errorMessage(),
                cached.receivedAt(),
                cached.processedAt(),
                cached.sentAt(),
                Boolean.TRUE,
                cached.failureType(),
                cached.providerMetadata(),
                cached.retryAfter());
    }
}
