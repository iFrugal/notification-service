package com.lazydevs.notification.kafka;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.core.config.NotificationProperties;
import lazydevs.persistence.connection.multitenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Kafka listener for notification requests.
 *
 * <p>Mirrors the REST path's tenant + caller-id wiring so Kafka-originated
 * requests get the same treatment as REST ones:
 * <ul>
 *   <li>{@link #TENANT_HEADER} ({@code X-Tenant-Id}) — DD-03. Sets
 *       {@link TenantContext} for the duration of {@code handleNotification}.</li>
 *   <li>{@link #CALLER_HEADER} ({@code X-Service-Id}) — DD-11. Stamped onto
 *       {@link NotificationRequest#setCallerId(String)} <em>only</em> if
 *       the request payload didn't already set it (body wins, matching
 *       DD-11 §request-precedence). Flows from there into the audit
 *       record, the response, and the idempotency dedup tuple.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "notification.kafka", name = "enabled", havingValue = "true")
public class NotificationKafkaListener {

    public static final String TENANT_HEADER = "X-Tenant-Id";

    /**
     * Calling-service identifier header (DD-11). Same name as the REST
     * filter's {@code X-Service-Id} so producers don't have to know which
     * transport their consumer is using.
     */
    public static final String CALLER_HEADER = "X-Service-Id";

    private final NotificationService notificationService;
    private final NotificationProperties properties;

    public NotificationKafkaListener(NotificationService notificationService,
                                      NotificationProperties properties) {
        this.notificationService = notificationService;
        this.properties = properties;
        log.info("Kafka listener initialized for topic: {}", properties.getKafka().getTopic());
    }

    @KafkaListener(
            topics = "${notification.kafka.topic:notifications}",
            groupId = "${notification.kafka.group-id:notification-service}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleNotification(
            @Payload NotificationRequest request,
            @Header(value = TENANT_HEADER, required = false) String tenantId,
            @Header(value = CALLER_HEADER, required = false) String callerId,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.debug("Received notification from Kafka: key={}, partition={}, offset={}, tenantId={}, callerId={}",
                key, partition, offset, tenantId, callerId);

        try {
            // Set tenant context (DD-03)
            String resolvedTenantId = tenantId != null ? tenantId : properties.getDefaultTenant();
            TenantContext.setTenantId(resolvedTenantId);

            // Caller id (DD-11): the request body wins if it set callerId
            // explicitly; otherwise the header value is stamped onto the
            // request before dispatch so DefaultNotificationService sees it
            // as if the REST filter had set it.
            if (!StringUtils.hasText(request.getCallerId()) && StringUtils.hasText(callerId)) {
                request.setCallerId(callerId);
            }

            // Process notification
            NotificationResponse response = notificationService.send(request);

            if (response.status().name().startsWith("FAIL") ||
                    response.status().name().equals("REJECTED")) {
                log.warn("Notification processing failed: requestId={}, status={}, error={}",
                        response.requestId(), response.status(), response.errorMessage());
            } else {
                log.info("Notification processed from Kafka: requestId={}, status={}",
                        response.requestId(), response.status());
            }

        } catch (Exception e) {
            log.error("Error processing notification from Kafka: key={}", key, e);
            // Don't rethrow - let Kafka commit the offset
            // Failed notifications are tracked via audit service

        } finally {
            // TenantContext was renamed clear() -> reset() in persistence-utils 1.0.46.
            TenantContext.reset();
        }
    }
}
