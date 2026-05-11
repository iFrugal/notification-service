package com.lazydevs.notification.rest.webhook;

/**
 * Thrown when a webhook callback fails its provider-specific signature
 * verification. The webhook controller turns this into HTTP 403 — see
 * DD-16 §"Why fail-403 rather than fail-silent": telling the provider
 * the signature failed lets a real provider (key rotated, misconfig)
 * see it in their own admin dashboard, while still giving an attacker
 * no information beyond "this endpoint exists."
 */
public class WebhookSignatureException extends RuntimeException {

    public WebhookSignatureException(String message) {
        super(message);
    }

    public WebhookSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
