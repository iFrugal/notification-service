package com.lazydevs.notification.channel.push.fcm;

import com.lazydevs.notification.api.model.FailureType;

import java.util.Objects;

/**
 * An {@link FcmAccessTokenProvider} could not obtain an access token.
 *
 * <p>The send fails with error code {@link #ERROR_CODE} and this failure type:
 * {@link FailureType#PERMANENT} when the credentials themselves are rejected
 * (for example {@code invalid_grant}, a deleted or disabled key),
 * {@link FailureType#TRANSIENT} when the token endpoint was unreachable or
 * answered with a server error.
 * The message must not contain the assertion, the private key or a token.
 *
 * @since 1.2.0
 */
public class FcmAuthenticationException extends RuntimeException {

    /** {@code SendResult.errorCode} of a send that failed for lack of an access token. */
    public static final String ERROR_CODE = "FCM_AUTH_FAILED";

    private static final long serialVersionUID = 1L;

    private final transient FailureType failureType;

    /**
     * @param message     what failed, without secrets
     * @param failureType how the send failure is classified
     * @param cause       the underlying failure, may be {@code null}
     */
    public FcmAuthenticationException(String message, FailureType failureType, Throwable cause) {
        super(message, cause);
        this.failureType = Objects.requireNonNull(failureType, "failureType");
    }

    /** @return how the send failure is classified */
    public FailureType failureType() {
        return failureType;
    }
}
