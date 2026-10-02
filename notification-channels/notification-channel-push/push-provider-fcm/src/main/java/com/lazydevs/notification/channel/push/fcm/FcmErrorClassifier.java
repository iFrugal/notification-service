package com.lazydevs.notification.channel.push.fcm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.FailureTypes;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Classifies FCM HTTP v1 errors for retry decisions.
 *
 * <p>FCM answers with a {@code google.rpc.Status} body whose {@code details} may hold a
 * {@code google.firebase.fcm.v1.FcmError} ({@code errorCode}) and a
 * {@code google.rpc.BadRequest} ({@code fieldViolations}).
 *
 * <table>
 *   <caption>Classification</caption>
 *   <tr><th>Answer</th><th>Classification</th></tr>
 *   <tr><td>400 {@code INVALID_ARGUMENT} on {@code message.token} (or {@code message.fid})</td>
 *       <td>PERMANENT, invalid-target event</td></tr>
 *   <tr><td>400 otherwise</td><td>PERMANENT</td></tr>
 *   <tr><td>404 {@code UNREGISTERED}</td><td>PERMANENT, invalid-target event</td></tr>
 *   <tr><td>403 {@code SENDER_ID_MISMATCH}</td><td>PERMANENT, invalid-target event</td></tr>
 *   <tr><td>429 {@code QUOTA_EXCEEDED}, or 429 without an FcmError</td>
 *       <td>TRANSIENT, {@code Retry-After} or else {@value #DEFAULT_QUOTA_RETRY_AFTER_SECONDS}s</td></tr>
 *   <tr><td>503 {@code UNAVAILABLE}, 500 {@code INTERNAL}, any 5xx</td>
 *       <td>TRANSIENT, {@code Retry-After} when present</td></tr>
 *   <tr><td>401 {@code THIRD_PARTY_AUTH_ERROR}</td><td>PERMANENT</td></tr>
 *   <tr><td>401 without an FcmError</td>
 *       <td>TRANSIENT; the sender invalidates the access token and resends once</td></tr>
 *   <tr><td>{@code UNSPECIFIED_ERROR}, or a 4xx without a {@code google.rpc.Status} body</td>
 *       <td>UNKNOWN</td></tr>
 *   <tr><td>no connection ({@code requestSent=false})</td><td>TRANSIENT</td></tr>
 *   <tr><td>timeout or lost connection after the request was sent</td>
 *       <td>AMBIGUOUS, or TRANSIENT with {@code timeout-classification: transient}</td></tr>
 * </table>
 */
final class FcmErrorClassifier {

    /** FCM asks for at least one minute after a quota error; used when 429 carries no Retry-After. */
    static final long DEFAULT_QUOTA_RETRY_AFTER_SECONDS = 60;
    static final Duration DEFAULT_QUOTA_RETRY_AFTER = Duration.ofSeconds(DEFAULT_QUOTA_RETRY_AFTER_SECONDS);

    static final String UNREGISTERED = "UNREGISTERED";
    static final String SENDER_ID_MISMATCH = "SENDER_ID_MISMATCH";
    static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    static final String UNAVAILABLE = "UNAVAILABLE";
    static final String INTERNAL = "INTERNAL";
    static final String THIRD_PARTY_AUTH_ERROR = "THIRD_PARTY_AUTH_ERROR";
    static final String UNSPECIFIED_ERROR = "UNSPECIFIED_ERROR";

    static final String CODE_CONNECT_FAILED = "FCM_CONNECT_FAILED";
    static final String CODE_TIMEOUT = "FCM_TIMEOUT";
    static final String CODE_CONNECTION_LOST = "FCM_CONNECTION_LOST";
    static final String CODE_TRANSPORT_ERROR = "FCM_TRANSPORT_ERROR";
    static final String CODE_UNAUTHENTICATED = "UNAUTHENTICATED";

    private static final String FCM_ERROR_TYPE = "type.googleapis.com/google.firebase.fcm.v1.FcmError";
    private static final String BAD_REQUEST_TYPE = "type.googleapis.com/google.rpc.BadRequest";

    private FcmErrorClassifier() {
    }

    /**
     * Classify a non-2xx answer.
     *
     * @param response     the answer
     * @param deviceTarget whether the message went to a device token or installation id
     * @param clock        the clock an HTTP-date {@code Retry-After} is measured against
     * @return the failure
     */
    static FcmFailure classify(FcmHttpResponse response, boolean deviceTarget, Clock clock) {
        int status = response.statusCode();
        Optional<ObjectNode> error = FcmJson.readObject(response.body())
                .map(body -> body.get("error"))
                .filter(ObjectNode.class::isInstance)
                .map(ObjectNode.class::cast);
        String message = error.map(e -> FcmJson.text(e, "message")).orElse("HTTP " + status);
        String rpcStatus = error.map(e -> FcmJson.text(e, "status")).orElse(null);
        String fcmCode = error.map(FcmErrorClassifier::fcmErrorCode).orElse(null);
        Duration hint = FailureTypes.parseRetryAfter(response.header("Retry-After"), clock).orElse(null);

        if (fcmCode != null) {
            switch (fcmCode) {
                case UNREGISTERED, SENDER_ID_MISMATCH -> {
                    return new FcmFailure(FailureType.PERMANENT, fcmCode, message, null, deviceTarget, false);
                }
                case INVALID_ARGUMENT -> {
                    boolean onTarget = deviceTarget && error.map(FcmErrorClassifier::violatesTarget).orElse(false);
                    return new FcmFailure(FailureType.PERMANENT, fcmCode, message, null, onTarget, false);
                }
                case QUOTA_EXCEEDED -> {
                    return new FcmFailure(FailureType.TRANSIENT, fcmCode, message,
                            hint != null ? hint : DEFAULT_QUOTA_RETRY_AFTER, false, false);
                }
                case UNAVAILABLE, INTERNAL -> {
                    return new FcmFailure(FailureType.TRANSIENT, fcmCode, message, hint, false, false);
                }
                case THIRD_PARTY_AUTH_ERROR -> {
                    return new FcmFailure(FailureType.PERMANENT, fcmCode, message
                            + " (check the APNs authentication key or certificate, or the web push keys,"
                            + " in the Firebase console)", null, false, false);
                }
                case UNSPECIFIED_ERROR -> {
                    return new FcmFailure(FailureType.UNKNOWN, fcmCode, message, null, false, false);
                }
                default -> {
                    // A code newer than this module: decide by the HTTP status below.
                }
            }
        }
        String code = fcmCode != null ? fcmCode : rpcStatus != null ? rpcStatus : "HTTP_" + status;
        if (status == 401) {
            return new FcmFailure(FailureType.TRANSIENT, rpcStatus != null ? rpcStatus : CODE_UNAUTHENTICATED,
                    message + " (the access token was rejected)", null, false, true);
        }
        if (status == 429) {
            return new FcmFailure(FailureType.TRANSIENT, code, message,
                    hint != null ? hint : DEFAULT_QUOTA_RETRY_AFTER, false, false);
        }
        if (status >= 500 || status == 408) {
            return new FcmFailure(FailureType.TRANSIENT, code, message, hint, false, false);
        }
        if (error.isPresent() && status >= 400) {
            if (status == 403) {
                message += " (check that the service account may send with the Firebase Cloud Messaging API"
                        + " and that the API is enabled for the project)";
            }
            return new FcmFailure(FailureTypes.fromHttpStatus(status), code, message, null, false, false);
        }
        return new FcmFailure(FailureType.UNKNOWN, code, message, null, false, false);
    }

    /**
     * Classify a call that got no answer.
     *
     * @param e              the transport failure
     * @param classification the tenant's {@code timeout-classification}
     * @return the failure
     */
    static FcmFailure classify(FcmTransportException e, FcmSettings.TimeoutClassification classification) {
        if (!e.requestSent()) {
            return FcmFailure.of(FailureType.TRANSIENT, CODE_CONNECT_FAILED,
                    "could not reach FCM: " + e.getMessage());
        }
        FailureType type = classification == FcmSettings.TimeoutClassification.TRANSIENT
                ? FailureType.TRANSIENT
                : FailureType.AMBIGUOUS;
        String code = e.timeout() ? CODE_TIMEOUT : CODE_CONNECTION_LOST;
        String suffix = type == FailureType.AMBIGUOUS
                ? " (FCM may have accepted the message, so it is not retried)"
                : "";
        return FcmFailure.of(type, code, (e.timeout() ? "no answer from FCM: " : "connection to FCM lost: ")
                + e.getMessage() + suffix);
    }

    /**
     * Classify a transport that threw something other than {@link FcmTransportException}.
     */
    static FcmFailure classify(RuntimeException e) {
        return FcmFailure.of(FailureType.UNKNOWN, CODE_TRANSPORT_ERROR,
                "the FCM HTTP transport failed: " + e.getClass().getSimpleName());
    }

    private static String fcmErrorCode(ObjectNode error) {
        JsonNode details = error.get("details");
        if (details == null || !details.isArray()) {
            return null;
        }
        for (JsonNode detail : details) {
            if (FCM_ERROR_TYPE.equals(FcmJson.text(detail, "@type"))) {
                return FcmJson.text(detail, "errorCode");
            }
        }
        return null;
    }

    private static boolean violatesTarget(ObjectNode error) {
        JsonNode details = error.get("details");
        if (details == null || !details.isArray()) {
            return false;
        }
        for (JsonNode detail : details) {
            if (BAD_REQUEST_TYPE.equals(FcmJson.text(detail, "@type"))
                    && detail.get("fieldViolations") instanceof JsonNode violations && violations.isArray()) {
                for (JsonNode violation : violations) {
                    String field = FcmJson.text(violation, "field");
                    if ("message.token".equals(field) || "message.fid".equals(field)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
