package com.lazydevs.notification.rest.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.webhook.WebhookRequest;
import com.lazydevs.notification.api.webhook.WebhookResponse;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSON answers and request helpers shared by the built-in Twilio and SES
 * {@link com.lazydevs.notification.api.webhook.WebhookHandler}s, so their
 * response bodies stay exactly what the 1.2 controller returned.
 */
final class WebhookBodies {

    static final String FIELD_ERROR = "error";
    static final String FIELD_STATUS = "status";
    static final String STATUS_ACCEPTED = "accepted";
    static final String SIGNATURE_FAILED = "signature verification failed";

    private static final ObjectMapper JSON = new ObjectMapper();

    private WebhookBodies() {
    }

    /** A JSON object body from alternating keys and values, in order. */
    static String object(String... keysAndValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        try {
            return JSON.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise a map of strings", e);
        }
    }

    static WebhookResponse error(int status, String message) {
        return WebhookResponse.json(status, object(FIELD_ERROR, message));
    }

    static WebhookResponse accepted(DeliveryEvent event) {
        return WebhookResponse.json(200, object(FIELD_STATUS, STATUS_ACCEPTED), List.of(event));
    }

    static WebhookResponse signatureFailed() {
        return WebhookResponse.signatureRejected(WebhookResponse.APPLICATION_JSON,
                object(FIELD_ERROR, SIGNATURE_FAILED));
    }

    /**
     * 404 for a path the handler does not serve, 405 for a method it does not
     * accept on its path, 415 for a body type it cannot read; {@code null} when
     * the request may proceed. The same answers Spring MVC gave the 1.2
     * controller's fixed routes.
     */
    static WebhookResponse route(WebhookRequest request, String path, String... contentTypes) {
        if (!path.equals(request.subPath())) {
            return error(404, "not found");
        }
        if (!request.isMethod(WebhookRequest.POST)) {
            return error(405, "method not allowed");
        }
        String contentType = request.header("Content-Type");
        if (contentType != null) {
            String type = contentType.toLowerCase(Locale.ROOT);
            for (String accepted : contentTypes) {
                if (type.startsWith(accepted)) {
                    return null;
                }
            }
            return error(415, "unsupported content type");
        }
        return null;
    }

    /** Decode an {@code application/x-www-form-urlencoded} body; the first value of a repeated name wins. */
    static Map<String, String> form(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) {
            return form;
        }
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            form.putIfAbsent(name, value);
        }
        return form;
    }

    static String sanitize(String s) {
        return s == null ? "null" : s.replaceAll("\\p{Cntrl}", "_");
    }
}
