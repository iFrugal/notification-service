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

/**
 * Kafka listener for notification requests.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "notification.kafka", name = "enabled", havingValue = "true")
public class NotificationKafkaListener {

    public static final String TENANT_HEADER = "X-Tenant-Id";

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
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.debug("Received notification from Kafka: key={}, partition={}, offset={}, tenantId={}",
                key, partition, offset, tenantId);

        try {
            // Set tenant context
            String resolvedTenantId = tenantId != null ? tenantId : properties.getDefaultTenant();
            TenantContext.setTenantId(resolvedTenantId);

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
