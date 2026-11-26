package com.lazydevs.notification.rest.webhook;

import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import com.lazydevs.notification.api.webhook.WebhookRequest;
import com.lazydevs.notification.api.webhook.WebhookResponse;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.metrics.NotificationMetrics;
import com.lazydevs.notification.core.webhook.WebhookDispatcher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * REST surface that ingests provider-side delivery callbacks (DD-16, DD-36).
 *
 * <p>One route, {@code GET|POST ${notification.rest.base-path}${notification.webhooks.base-path}/{handlerId}/**},
 * hands the raw request to the {@link WebhookDispatcher}, which calls the
 * {@link com.lazydevs.notification.api.webhook.WebhookHandler} registered under
 * {@code handlerId}. Built-in handlers keep the 1.2 paths:
 * <ul>
 *   <li>{@code /twilio/status} - Twilio SMS status callback ({@link TwilioWebhookHandler});</li>
 *   <li>{@code /ses/sns} - SES events through SNS ({@link SesWebhookHandler}).</li>
 * </ul>
 * Provider modules add their own, for example {@code /meta} for Meta WhatsApp.
 *
 * <p>Registered by {@code NotificationRestAutoConfiguration} only when both
 * {@code notification.rest.enabled=true} and
 * {@code notification.webhooks.enabled=true}. An unknown handler id is 404;
 * a failed signature is 403 (DD-16 "fail-403 rather than fail-silent").
 */
@RestController
@RequestMapping("${notification.rest.base-path:/api/v1}${notification.webhooks.base-path:/webhooks}")
@Tag(name = "Webhooks",
        description = "Provider delivery-callback ingestion (DD-16, DD-36). "
                + "Each handler verifies the provider's signature scheme "
                + "before parsing the body; failed verification returns "
                + "`403`. Successfully parsed events are dispatched to "
                + "every registered DeliveryEventListener.")
public class WebhookController {

    private final WebhookDispatcher dispatcher;

    public WebhookController(WebhookDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    /**
     * The 1.2 constructor: a controller over the built-in Twilio and SES
     * handlers only.
     *
     * @param properties the notification properties (Twilio and SES settings)
     * @param listeners  the delivery event listeners, may be {@code null}
     * @param metrics    the metrics, when Micrometer is present
     * @deprecated since 1.3.0; build a {@link WebhookDispatcher} with the handlers you need
     *             and use {@link #WebhookController(WebhookDispatcher)}
     */
    @Deprecated(since = "1.3.0", forRemoval = true)
    public WebhookController(NotificationProperties properties,
                             List<DeliveryEventListener> listeners,
                             Optional<NotificationMetrics> metrics) {
        this(new WebhookDispatcher(
                List.of(new TwilioWebhookHandler(properties), new SesWebhookHandler(properties)),
                listeners == null ? List.of() : listeners,
                metrics));
    }

    @RequestMapping(value = "/{handlerId}/{*subPath}", method = {RequestMethod.GET, RequestMethod.POST})
    @Operation(summary = "Provider callback (DD-16, DD-36)",
            description = "Routes the callback to the webhook handler registered under handlerId, "
                    + "for example twilio/status, ses/sns or meta. The handler verifies the "
                    + "provider's signature over the raw body before parsing it.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Callback accepted."),
                    @ApiResponse(responseCode = "400", description = "Callback body could not be parsed."),
                    @ApiResponse(responseCode = "403", description = "Signature verification failed."),
                    @ApiResponse(responseCode = "404", description = "No handler for this path, or it is not enabled.")
            })
    public ResponseEntity<byte[]> handle(@PathVariable("handlerId") String handlerId,
                                         @PathVariable("subPath") String subPath,
                                         @RequestBody(required = false) byte[] body,
                                         HttpServletRequest request) {
        WebhookRequest webhookRequest = new WebhookRequest(
                request.getMethod(),
                subPath,
                headers(request),
                queryParameters(request.getQueryString()),
                body,
                reconstructRequestUrl(request));
        WebhookResponse response = dispatcher.dispatch(handlerId, webhookRequest);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status());
        if (response.body() == null) {
            return builder.build();
        }
        if (response.contentType() != null) {
            MediaType type = MediaType.parseMediaType(response.contentType());
            builder.contentType(type.getCharset() == null && "text".equals(type.getType())
                    ? new MediaType(type, StandardCharsets.UTF_8)
                    : type);
        }
        return builder.body(response.body().getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, List<String>> headers(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, Collections.list(request.getHeaders(name)));
        }
        return headers;
    }

    /**
     * The query string's parameters only. {@code getParameterMap()} would
     * also contain a form body's fields.
     */
    static Map<String, List<String>> queryParameters(String queryString) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        if (queryString == null || queryString.isEmpty()) {
            return query;
        }
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            query.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return query;
    }

    /**
     * Reconstruct the URL the provider used. Twilio computes its HMAC
     * over the URL it called, including query string. Behind a proxy
     * this URL must match what the proxy registered with Twilio -
     * operators terminating TLS at a proxy may need to set
     * {@code X-Forwarded-Proto} / {@code X-Forwarded-Host} on the
     * proxy and make Spring Boot honour them
     * ({@code server.forward-headers-strategy=framework}).
     */
    static String reconstructRequestUrl(HttpServletRequest request) {
        // getRequestURL() returns a StringBuffer; copy into StringBuilder
        // for the typical lock-free local mutation we need here.
        StringBuilder url = new StringBuilder(request.getRequestURL());
        if (request.getQueryString() != null) {
            url.append('?').append(request.getQueryString());
        }
        return url.toString();
    }
}
