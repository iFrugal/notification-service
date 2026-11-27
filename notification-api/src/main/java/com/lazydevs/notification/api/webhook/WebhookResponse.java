package com.lazydevs.notification.api.webhook;

import com.lazydevs.notification.api.delivery.DeliveryEvent;

import java.util.List;

/**
 * What a {@link WebhookHandler} answers the provider, and the delivery events
 * it parsed from the callback (DD-36).
 *
 * <p>The dispatcher hands {@link #events()} to the delivery event listeners and
 * then writes status, content type and body back to the provider.
 *
 * @param status            the HTTP status code
 * @param contentType       the response content type; {@code null} when there is no body
 * @param body              the response body, may be {@code null}
 * @param events            the parsed delivery events, never {@code null}
 * @param signatureRejected whether the callback failed signature verification;
 *                          the dispatcher counts it in the
 *                          {@code notification.webhook.signature.failed.total} metric
 * @since 1.2.2
 */
public record WebhookResponse(
        int status,
        String contentType,
        String body,
        List<DeliveryEvent> events,
        boolean signatureRejected) {

    /** Content type of a JSON body. */
    public static final String APPLICATION_JSON = "application/json";

    /** Content type of a plain text body. */
    public static final String TEXT_PLAIN = "text/plain";

    /** HTTP 403, the answer to a failed signature verification (DD-16). */
    public static final int FORBIDDEN = 403;

    /**
     * Copies the events.
     */
    public WebhookResponse {
        events = events == null ? List.of() : List.copyOf(events);
    }

    /**
     * A JSON answer without events.
     *
     * @param status the HTTP status
     * @param json   the JSON body
     * @return the response
     */
    public static WebhookResponse json(int status, String json) {
        return new WebhookResponse(status, APPLICATION_JSON, json, List.of(), false);
    }

    /**
     * A JSON answer that carries parsed events.
     *
     * @param status the HTTP status
     * @param json   the JSON body
     * @param events the parsed events
     * @return the response
     */
    public static WebhookResponse json(int status, String json, List<DeliveryEvent> events) {
        return new WebhookResponse(status, APPLICATION_JSON, json, events, false);
    }

    /**
     * A plain text answer without events, for example a subscription challenge echo.
     *
     * @param status the HTTP status
     * @param text   the body
     * @return the response
     */
    public static WebhookResponse text(int status, String text) {
        return new WebhookResponse(status, TEXT_PLAIN, text, List.of(), false);
    }

    /**
     * HTTP 403 for a callback whose signature did not verify.
     *
     * @param contentType the content type of {@code body}, may be {@code null}
     * @param body        the body, may be {@code null}
     * @return the response
     */
    public static WebhookResponse signatureRejected(String contentType, String body) {
        return new WebhookResponse(FORBIDDEN, contentType, body, List.of(), true);
    }
}
