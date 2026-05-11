package com.lazydevs.notification.rest.webhook;

import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link DeliveryEventListener} (DD-16). Logs at INFO. Useful
 * as a "wire up the webhook surface, prove the signature path works,
 * then plug in a real listener" baseline.
 *
 * <p>Registered only when {@code notification.webhooks.enabled=true}
 * <strong>and</strong> no other listener is on the classpath. Operators
 * who provide their own listener bean (their own
 * {@link DeliveryEventListener} implementation) take precedence.
 *
 * <p>Note: only one listener is registered by default. If multiple
 * listeners are required, register them as a {@code List<DeliveryEventListener>}
 * — the webhook controller dispatches to all listeners on its
 * collection.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "notification.webhooks", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean(DeliveryEventListener.class)
public class LoggingDeliveryEventListener implements DeliveryEventListener {

    public LoggingDeliveryEventListener() {
        log.info("LoggingDeliveryEventListener registered — delivery events will be logged at INFO. "
                + "Replace with a custom DeliveryEventListener bean to fan out to your audit pipeline.");
    }

    @Override
    public void onEvent(DeliveryEvent event) {
        // Sanitize free-form provider strings before logging — they
        // come straight off the wire and could carry control characters
        // (CRLF log injection in particular). Same defensive pattern
        // DefaultNotificationService uses for caller-supplied strings.
        log.info("delivery event: provider={} status={} providerMessageId={} providerEventId={} reason={}",
                sanitize(event.providerName()),
                event.status(),
                sanitize(event.providerMessageId()),
                sanitize(event.providerEventId()),
                sanitize(event.reason()));
    }

    private static String sanitize(String s) {
        return s == null ? "null" : s.replaceAll("[\\p{Cntrl}]", "_");
    }
}
