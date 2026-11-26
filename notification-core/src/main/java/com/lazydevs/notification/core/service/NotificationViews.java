package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.model.NotificationAudit;
import com.lazydevs.notification.api.model.NotificationView;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Builds a {@link NotificationView} from an audit record and the latest
 * delivery event for its provider message id (DD-33, the DD-18 join).
 */
@Slf4j
final class NotificationViews {

    private final DeliveryEventStore deliveryEvents;

    /**
     * @param deliveryEvents the delivery event store, or {@code null} when
     *                       delivery events are not kept
     */
    NotificationViews(DeliveryEventStore deliveryEvents) {
        this.deliveryEvents = deliveryEvents;
    }

    NotificationView toView(NotificationAudit audit) {
        DeliveryEvent latest = latestDeliveryEvent(audit).orElse(null);
        java.time.Instant deliveredAt = audit.getDeliveredAt();
        if (deliveredAt == null && latest != null && latest.status() == DeliveryStatus.DELIVERED) {
            deliveredAt = latest.timestamp();
        }
        return new NotificationView(
                audit.getRequestId(),
                audit.getTenantId(),
                audit.getCallerId(),
                audit.getIdempotencyKey(),
                audit.getCorrelationId(),
                audit.getChannel(),
                audit.getNotificationType(),
                audit.getProvider(),
                audit.getTemplateId(),
                audit.getRecipientCount(),
                audit.getMetadata(),
                audit.getStatus(),
                audit.getFailureType(),
                audit.getErrorCode(),
                audit.getErrorMessage(),
                audit.getProviderMessageId(),
                audit.getReceivedAt(),
                audit.getSentAt(),
                deliveredAt,
                audit.getFailedAt(),
                audit.getUpdatedAt(),
                latest == null ? null : latest.status().name(),
                latest == null ? null : latest.timestamp());
    }

    /**
     * The latest event by timestamp. A failing store is logged and treated
     * as "no event", so the view still answers.
     */
    private Optional<DeliveryEvent> latestDeliveryEvent(NotificationAudit audit) {
        if (deliveryEvents == null || isBlank(audit.getProvider()) || isBlank(audit.getProviderMessageId())) {
            return Optional.empty();
        }
        try {
            return deliveryEvents.findByProviderMessageId(audit.getProvider(), audit.getProviderMessageId())
                    .flatMap(NotificationViews::latest);
        } catch (RuntimeException e) {
            log.warn("Delivery event lookup failed for the status view: {}", e.toString());
            return Optional.empty();
        }
    }

    private static Optional<DeliveryEvent> latest(List<DeliveryEvent> events) {
        return events.stream().max(Comparator.comparing(DeliveryEvent::timestamp));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
