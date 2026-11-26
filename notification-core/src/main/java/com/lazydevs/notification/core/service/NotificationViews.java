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
        List<DeliveryEvent> events = deliveryEventsOf(audit);
        DeliveryEvent latest = latest(events).orElse(null);
        java.time.Instant deliveredAt = audit.getDeliveredAt();
        if (deliveredAt == null && latest != null && latest.status() == DeliveryStatus.DELIVERED) {
            deliveredAt = latest.timestamp();
        } else if (deliveredAt == null && latest != null && latest.status() == DeliveryStatus.READ) {
            // READ implies DELIVERED: the delivery's own time when its callback came, else the read time
            deliveredAt = events.stream()
                    .filter(event -> event.status() == DeliveryStatus.DELIVERED)
                    .map(DeliveryEvent::timestamp)
                    .min(Comparator.naturalOrder())
                    .orElse(latest.timestamp());
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
     * The delivery events of the audit record's provider message id. A failing
     * store is logged and treated as "no event", so the view still answers.
     */
    private List<DeliveryEvent> deliveryEventsOf(NotificationAudit audit) {
        if (deliveryEvents == null || isBlank(audit.getProvider()) || isBlank(audit.getProviderMessageId())) {
            return List.of();
        }
        try {
            return deliveryEvents.findByProviderMessageId(audit.getProvider(), audit.getProviderMessageId())
                    .orElse(List.of());
        } catch (RuntimeException e) {
            log.warn("Delivery event lookup failed for the status view: {}", e.toString());
            return List.of();
        }
    }

    /**
     * The latest event by timestamp, which never moves a delivery backwards
     * ({@link DeliveryStatus}): when it is {@code SENT} or {@code DELIVERED}
     * and a further step ({@code DELIVERED}, {@code READ}) is known, that
     * step is shown instead, because providers such as WhatsApp report
     * {@code READ} before {@code DELIVERED} and can stamp both with the same
     * second. Events without these statuses are chosen by timestamp alone.
     */
    static Optional<DeliveryEvent> latest(List<DeliveryEvent> events) {
        Optional<DeliveryEvent> byTime = events.stream().max(Comparator.comparing(DeliveryEvent::timestamp));
        int reached = byTime.map(event -> progress(event.status())).orElse(0);
        if (reached == 0) {
            return byTime;
        }
        Optional<DeliveryEvent> furthest = events.stream()
                .filter(event -> progress(event.status()) > reached)
                .max(Comparator.<DeliveryEvent>comparingInt(event -> progress(event.status()))
                        .thenComparing(DeliveryEvent::timestamp));
        return furthest.isPresent() ? furthest : byTime;
    }

    /** Step of a successful delivery: SENT 1, DELIVERED 2, READ 3; 0 for every other status. */
    private static int progress(DeliveryStatus status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case SENT -> 1;
            case DELIVERED -> 2;
            case READ -> 3;
            default -> 0;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
