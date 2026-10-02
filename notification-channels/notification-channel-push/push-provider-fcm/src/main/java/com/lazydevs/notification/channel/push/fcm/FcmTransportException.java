package com.lazydevs.notification.channel.push.fcm;

import java.io.IOException;

/**
 * No response arrived for an {@link FcmHttpRequest}.
 *
 * <p>{@link #requestSent()} decides the retry classification of a send:
 * <ul>
 *   <li>{@code false} (connection refused, DNS failure, TLS handshake failure,
 *       connect timeout) means FCM cannot have received the message, so the
 *       failure is {@code TRANSIENT};</li>
 *   <li>{@code true} (a timeout, or a lost connection, after the request went
 *       out) means FCM may have accepted it, so the failure is {@code AMBIGUOUS}
 *       unless the tenant sets {@code timeout-classification: transient}.</li>
 * </ul>
 *
 * @since 1.2.0
 */
public class FcmTransportException extends IOException {

    private static final long serialVersionUID = 1L;

    private final boolean requestSent;
    private final boolean timeout;

    /**
     * @param message     what failed; must not contain credentials
     * @param cause       the underlying failure, may be {@code null}
     * @param requestSent whether the request may have reached the server
     * @param timeout     whether the failure was a timeout
     */
    public FcmTransportException(String message, Throwable cause, boolean requestSent, boolean timeout) {
        super(message, cause);
        this.requestSent = requestSent;
        this.timeout = timeout;
    }

    /**
     * The request cannot have reached the server, for example the connection was refused.
     *
     * @param message what failed
     * @param cause   the underlying failure, may be {@code null}
     * @return the exception
     */
    public static FcmTransportException notSent(String message, Throwable cause) {
        return new FcmTransportException(message, cause, false, false);
    }

    /**
     * The request went out, and no complete response came back in time.
     *
     * @param message what failed
     * @param cause   the underlying failure, may be {@code null}
     * @return the exception
     */
    public static FcmTransportException timedOut(String message, Throwable cause) {
        return new FcmTransportException(message, cause, true, true);
    }

    /**
     * The request may have gone out, and the connection failed before a response arrived.
     *
     * @param message what failed
     * @param cause   the underlying failure, may be {@code null}
     * @return the exception
     */
    public static FcmTransportException afterSend(String message, Throwable cause) {
        return new FcmTransportException(message, cause, true, false);
    }

    /** @return whether the request may have reached the server */
    public boolean requestSent() {
        return requestSent;
    }

    /** @return whether the failure was a timeout */
    public boolean timeout() {
        return timeout;
    }
}
