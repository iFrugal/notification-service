package com.lazydevs.notification.core.webhook;

import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import com.lazydevs.notification.api.util.PiiMasking;
import com.lazydevs.notification.api.webhook.WebhookHandler;
import com.lazydevs.notification.api.webhook.WebhookRequest;
import com.lazydevs.notification.api.webhook.WebhookResponse;
import com.lazydevs.notification.core.metrics.NotificationMetrics;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Routes a provider callback to the {@link WebhookHandler} registered under
 * its id and hands the parsed events to every {@link DeliveryEventListener}
 * (DD-36).
 *
 * <p>Servlet-free, so any HTTP layer can mount it: the REST module's
 * {@code WebhookController} does, and an application without that module
 * calls {@link #dispatch(String, WebhookRequest)} from its own endpoint.
 *
 * <ul>
 *   <li>An unknown handler id is HTTP 404: a URL registered with a provider
 *       that nothing serves should be loud, not a silent 200.</li>
 *   <li>A handler that throws is HTTP 500, logged; the provider will retry.</li>
 *   <li>A signature-rejected answer counts in
 *       {@code notification.webhook.signature.failed.total{provider=<id>}}.</li>
 *   <li>Every event counts in
 *       {@code notification.delivery-events.received.total{provider, status}}
 *       and goes to every listener, in order, on the calling thread. A listener
 *       that throws is logged and skipped; the provider still gets the
 *       handler's answer, so a broken listener cannot make it retry forever.</li>
 * </ul>
 *
 * @since 1.2.2
 */
@Slf4j
public class WebhookDispatcher {

    /** Body of the 404 answer for an id no handler serves. */
    static final String NOT_FOUND_BODY = "{\"error\":\"no webhook handler is registered for this path\"}";

    /** Body of the 500 answer when a handler throws. */
    static final String HANDLER_FAILED_BODY = "{\"error\":\"webhook handler failed\"}";

    private static final int MAX_LOGGED_LENGTH = 64;

    private static final Pattern HANDLER_ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private final Map<String, WebhookHandler> handlers;
    private final Iterable<DeliveryEventListener> listeners;
    private final Optional<NotificationMetrics> metrics;

    /**
     * @param handlers  the handlers; ids must be unique and match {@code [a-z0-9][a-z0-9-]*}
     * @param listeners the listeners, iterated on every dispatch (a lazy
     *                  {@code Iterable} lets them be resolved after this dispatcher is built)
     * @param metrics   the metrics, when Micrometer is present
     * @throws IllegalStateException when two handlers share an id or an id is malformed
     */
    public WebhookDispatcher(Collection<? extends WebhookHandler> handlers,
                             Iterable<DeliveryEventListener> listeners,
                             Optional<NotificationMetrics> metrics) {
        this.handlers = index(handlers);
        this.listeners = Objects.requireNonNull(listeners, "listeners");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        log.info("Webhook handlers registered: {}", this.handlers.keySet());
    }

    /** @return the registered handler ids, in registration order */
    public Set<String> handlerIds() {
        return handlers.keySet();
    }

    /**
     * Handle one callback.
     *
     * @param handlerId the handler id, the first path segment below the webhooks base path
     * @param request   the callback
     * @return the answer for the provider, never {@code null}
     */
    public WebhookResponse dispatch(String handlerId, WebhookRequest request) {
        Objects.requireNonNull(request, "request");
        WebhookHandler handler = handlerId == null ? null : handlers.get(handlerId);
        if (handler == null) {
            log.warn("Webhook callback for unknown handler '{}'", sanitize(handlerId));
            return WebhookResponse.json(404, NOT_FOUND_BODY);
        }
        WebhookResponse response;
        try {
            response = handler.handle(request);
        } catch (RuntimeException e) {
            log.error("Webhook handler '{}' threw on {} {}: {}", handler.id(), request.method(),
                    sanitize(request.subPath()), PiiMasking.redact(e.toString()));
            return WebhookResponse.json(500, HANDLER_FAILED_BODY);
        }
        if (response == null) {
            log.error("Webhook handler '{}' returned no response", handler.id());
            return WebhookResponse.json(500, HANDLER_FAILED_BODY);
        }
        if (response.signatureRejected()) {
            metrics.ifPresent(m -> m.recordWebhookSignatureFailed(handler.id()));
        }
        response.events().forEach(this::publish);
        return response;
    }

    private void publish(DeliveryEvent event) {
        metrics.ifPresent(m -> m.recordDeliveryEventReceived(event.providerName(), event.status()));
        for (DeliveryEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException e) {
                // The SPI contract is "must not throw"; trust nothing, so a bad
                // listener cannot break the callback. The provider still gets 200.
                log.warn("DeliveryEventListener {} threw, continuing: {}",
                        listener.getClass().getSimpleName(), PiiMasking.redact(e.toString()));
            }
        }
    }

    private static Map<String, WebhookHandler> index(Collection<? extends WebhookHandler> handlers) {
        Map<String, WebhookHandler> byId = new LinkedHashMap<>();
        if (handlers == null) {
            return Collections.unmodifiableMap(byId);
        }
        for (WebhookHandler handler : handlers) {
            String id = handler.id();
            if (id == null || !HANDLER_ID.matcher(id).matches()) {
                throw new IllegalStateException("WebhookHandler " + handler.getClass().getName()
                        + " has an invalid id '" + id + "'; use lowercase letters, digits and '-'");
            }
            WebhookHandler existing = byId.putIfAbsent(id, handler);
            if (existing != null) {
                throw new IllegalStateException("Two WebhookHandlers with id '" + id + "': "
                        + existing.getClass().getName() + " and " + handler.getClass().getName());
            }
        }
        return Collections.unmodifiableMap(byId);
    }

    /** Strips control characters and caps the length: the value comes from the URL. */
    private static String sanitize(String s) {
        if (s == null) {
            return "null";
        }
        String clean = s.replaceAll("\\p{Cntrl}", "_");
        return clean.length() > MAX_LOGGED_LENGTH ? clean.substring(0, MAX_LOGGED_LENGTH) + "..." : clean;
    }
}
