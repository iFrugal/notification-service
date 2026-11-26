package com.lazydevs.notification.channel.whatsapp.meta;

import java.io.IOException;

/**
 * No response arrived for a {@link MetaHttpRequest}.
 *
 * <p>{@link #requestSent()} decides the classification of a send:
 * {@code false} (connection refused, DNS failure, TLS handshake failure,
 * connect timeout) means Meta cannot have received the message, so the failure
 * is {@code TRANSIENT} with {@code CONNECT_FAILED}; {@code true} (a timeout, a
 * lost connection or an interrupt after the request went out) means Meta may
 * have accepted it, so the failure is {@code AMBIGUOUS} with
 * {@code NO_CONFIRMATION}: the Cloud API has no idempotency key, and a resend
 * could deliver the message twice.
 *
 * @since 1.4.0
 */
public class MetaTransportException extends IOException {

    private static final long serialVersionUID = 1L;

    private final boolean requestSent;

    /**
     * @param message     what failed; must not contain credentials
     * @param cause       the underlying failure, may be {@code null}
     * @param requestSent whether the request may have reached Meta
     */
    public MetaTransportException(String message, Throwable cause, boolean requestSent) {
        super(message, cause);
        this.requestSent = requestSent;
    }

    /**
     * The request cannot have reached Meta, for example the connection was refused.
     *
     * @param message what failed
     * @param cause   the underlying failure, may be {@code null}
     * @return the exception
     */
    public static MetaTransportException notSent(String message, Throwable cause) {
        return new MetaTransportException(message, cause, false);
    }

    /**
     * The request may have gone out, and no complete response came back.
     *
     * @param message what failed
     * @param cause   the underlying failure, may be {@code null}
     * @return the exception
     */
    public static MetaTransportException afterSend(String message, Throwable cause) {
        return new MetaTransportException(message, cause, true);
    }

    /** @return whether the request may have reached Meta */
    public boolean requestSent() {
        return requestSent;
    }
}
