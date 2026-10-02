package com.lazydevs.notification.channel.push.fcm;

import com.lazydevs.notification.api.model.FailureType;

import java.time.Duration;
import java.util.Objects;

/**
 * The classification of one failed FCM call.
 *
 * @param type          retry classification
 * @param code          FCM {@code errorCode} (for example {@code UNREGISTERED}), the
 *                      {@code google.rpc.Status} status, or one of the module's {@code FCM_*} codes
 * @param message       human-readable reason, without credentials or raw targets
 * @param retryAfter    how long FCM asked to wait, or {@code null}
 * @param invalidTarget whether FCM rejected the device token or installation id itself,
 *                      so the application should stop using it
 * @param refreshToken  whether the call was rejected for its access token, so a fresh
 *                      token may succeed
 */
record FcmFailure(FailureType type, String code, String message, Duration retryAfter, boolean invalidTarget,
                  boolean refreshToken) {

    FcmFailure {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(code, "code");
    }

    static FcmFailure of(FailureType type, String code, String message) {
        return new FcmFailure(type, code, message, null, false, false);
    }
}
