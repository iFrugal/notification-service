package com.lazydevs.notification.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only status of one notification (DD-33), returned by
 * {@link com.lazydevs.notification.api.NotificationService#find} and
 * {@link com.lazydevs.notification.api.NotificationService#findByIdempotencyKey}.
 *
 * <p>Built from the audit record joined with the latest delivery event. It
 * never carries recipient addresses or numbers: only {@link #recipientCount}.
 *
 * <p>Lifecycle as seen through the view:
 * <ul>
 *   <li>{@code PROCESSING} - received and claimed, the send is under way;</li>
 *   <li>{@code SENT} - the provider accepted it ({@link #sentAt});</li>
 *   <li>{@link #deliveryStatus} {@code DELIVERED} (or a bounce) - a provider
 *       callback arrived ({@link #deliveredAt} for a delivery);</li>
 *   <li>{@code FAILED} or {@code REJECTED} - with {@link #failureType},
 *       {@link #errorCode} and {@link #failedAt}; error code
 *       {@code IN_PROGRESS_TIMEOUT} with failure type {@code AMBIGUOUS} for a
 *       send settled by the idempotency in-progress timeout (DD-32).</li>
 * </ul>
 * An idempotent replay creates no record of its own: the view of the key is
 * the view of the request that first used it.
 *
 * @param requestId         request id
 * @param tenantId          tenant id
 * @param callerId          calling service, may be {@code null}
 * @param idempotencyKey    idempotency key, may be {@code null}
 * @param correlationId     correlation id, may be {@code null}
 * @param channel           channel
 * @param notificationType  notification type
 * @param provider          provider that handled the send, {@code null} before dispatch
 * @param templateId        explicit template id of the request, may be {@code null}
 * @param recipientCount    number of recipient addresses, {@code null} when unknown
 * @param metadata          the request's own metadata map, as the caller sent it
 * @param status            send status
 * @param failureType       failure classification, {@code null} unless failed
 * @param errorCode         error code, {@code null} unless failed
 * @param errorMessage      error message after the {@code MessageScrubber}
 * @param providerMessageId provider message id, may be {@code null}
 * @param receivedAt        when the request was received
 * @param sentAt            when the provider accepted it
 * @param deliveredAt       when delivery was confirmed by a provider event
 * @param failedAt          when the send was recorded as failed
 * @param updatedAt         when the record last changed
 * @param deliveryStatus    status of the latest delivery event for the provider
 *                          message id, as text (for example {@code DELIVERED},
 *                          {@code BOUNCED}); {@code null} when none arrived.
 *                          Text, not an enum, so new statuses need no change here.
 * @param deliveryStatusAt  timestamp of that delivery event
 * @since 1.2.1
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationView(
        String requestId,
        String tenantId,
        String callerId,
        String idempotencyKey,
        String correlationId,
        Channel channel,
        String notificationType,
        String provider,
        String templateId,
        Integer recipientCount,
        Map<String, String> metadata,
        NotificationStatus status,
        FailureType failureType,
        String errorCode,
        String errorMessage,
        String providerMessageId,
        Instant receivedAt,
        Instant sentAt,
        Instant deliveredAt,
        Instant failedAt,
        Instant updatedAt,
        String deliveryStatus,
        Instant deliveryStatusAt) {

    public NotificationView {
        metadata = metadata == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
