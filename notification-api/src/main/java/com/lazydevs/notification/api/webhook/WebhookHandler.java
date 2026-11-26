package com.lazydevs.notification.api.webhook;

/**
 * Handles the delivery callbacks of one provider, or one provider account
 * (DD-36).
 *
 * <p>Servlet-free: the host hands over a {@link WebhookRequest} and writes the
 * returned {@link WebhookResponse} back. The library's REST module routes
 * {@code GET|POST <rest base path><webhooks base path>/{id}/**} to the handler
 * whose {@link #id()} matches, through the core {@code WebhookDispatcher}; an
 * application without the REST module can call the dispatcher from its own
 * controller.
 *
 * <p>A handler verifies the provider's signature over the raw body before it
 * trusts anything in the request, and answers a failed verification with
 * {@link WebhookResponse#signatureRejected(String, String)}. It parses the
 * callback into {@link WebhookResponse#events()}; it does not call delivery
 * event listeners itself, the dispatcher does.
 *
 * <p>Implementations must be thread-safe and should not throw: bad input is a
 * 4xx response. A thrown exception becomes HTTP 500, and most providers retry
 * a 5xx.
 *
 * @since 1.4.0
 */
public interface WebhookHandler {

    /**
     * The first path segment this handler serves, for example {@code twilio}
     * or {@code meta}. Unique among the registered handlers; lowercase letters,
     * digits and {@code -} only.
     *
     * @return the handler id
     */
    String id();

    /**
     * Handle one callback.
     *
     * @param request the callback, never {@code null}
     * @return the answer for the provider, never {@code null}
     */
    WebhookResponse handle(WebhookRequest request);
}
