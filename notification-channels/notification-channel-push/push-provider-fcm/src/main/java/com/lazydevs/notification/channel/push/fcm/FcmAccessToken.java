package com.lazydevs.notification.channel.push.fcm;

import java.time.Instant;
import java.util.Objects;

/**
 * An OAuth 2.0 access token for the FCM HTTP v1 API.
 *
 * @param value     the bearer token; never logged, {@link #toString()} masks it
 * @param expiresAt when the token stops being valid
 * @since 1.2.0
 */
public record FcmAccessToken(String value, Instant expiresAt) {

    /**
     * Validates the arguments.
     */
    public FcmAccessToken {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (value.isBlank()) {
            throw new IllegalArgumentException("access token value is blank");
        }
    }

    /** Never prints the token. */
    @Override
    public String toString() {
        return "FcmAccessToken[value=****, expiresAt=" + expiresAt + "]";
    }
}
