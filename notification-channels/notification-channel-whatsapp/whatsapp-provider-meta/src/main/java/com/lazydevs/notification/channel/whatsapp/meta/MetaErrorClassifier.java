package com.lazydevs.notification.channel.whatsapp.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.FailureTypes;
import com.lazydevs.notification.api.util.PiiMasking;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

/**
 * Classifies Graph API answers and transport failures (DD-35). Graph error
 * codes are checked first, because Meta reports most of them under HTTP 400;
 * the HTTP status decides only when the code is not one of these.
 *
 * <table>
 *   <caption>Classification</caption>
 *   <tr><th>Condition</th><th>FailureType</th><th>errorCode</th></tr>
 *   <tr><td>no connection</td><td>TRANSIENT</td><td>CONNECT_FAILED</td></tr>
 *   <tr><td>timeout, lost connection, interrupt, 2xx without a message id</td>
 *       <td>AMBIGUOUS</td><td>NO_CONFIRMATION</td></tr>
 *   <tr><td>code 131056</td><td>TRANSIENT, max(Retry-After, floor)</td><td>PAIR_RATE_LIMITED</td></tr>
 *   <tr><td>codes 4, 80007, 130429; HTTP 429</td><td>TRANSIENT, Retry-After</td><td>THROTTLED</td></tr>
 *   <tr><td>codes 368, 131031, 131042</td><td>PERMANENT, health DOWN</td><td>ACCOUNT_RESTRICTED</td></tr>
 *   <tr><td>code 131047</td><td>PERMANENT</td><td>REENGAGEMENT_REQUIRED</td></tr>
 *   <tr><td>code 131026</td><td>PERMANENT</td><td>RECIPIENT_UNDELIVERABLE</td></tr>
 *   <tr><td>codes 132000-132999</td><td>PERMANENT</td><td>TEMPLATE_REJECTED</td></tr>
 *   <tr><td>codes 190, 10, 200; HTTP 401, 403</td><td>PERMANENT, health DOWN</td><td>CREDENTIAL_REJECTED</td></tr>
 *   <tr><td>codes 1, 2, 131000, 131016, 133004; HTTP 5xx, 408</td><td>TRANSIENT, Retry-After</td>
 *       <td>PROVIDER_UNAVAILABLE</td></tr>
 *   <tr><td>anything else</td><td>PERMANENT</td><td>REJECTED</td></tr>
 * </table>
 */
final class MetaErrorClassifier {

    static final int CODE_PAIR_RATE_LIMIT = 131056;
    static final int CODE_REENGAGEMENT = 131047;
    static final int CODE_UNDELIVERABLE = 131026;
    static final int TEMPLATE_CODE_MIN = 132000;
    static final int TEMPLATE_CODE_MAX = 132999;

    static final Set<Integer> THROTTLE_CODES = Set.of(4, 80007, 130429);
    static final Set<Integer> ACCOUNT_CODES = Set.of(368, 131031, 131042);
    static final Set<Integer> CREDENTIAL_CODES = Set.of(190, 10, 200);
    static final Set<Integer> UNAVAILABLE_CODES = Set.of(1, 2, 131000, 131016, 133004);

    private static final int HTTP_REQUEST_TIMEOUT = 408;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_SERVER_ERROR = 500;
    private static final int MAX_DETAIL_LENGTH = 300;

    /**
     * One classified failure.
     *
     * @param type       retry classification
     * @param errorCode  one of {@link MetaErrorCodes}
     * @param message    what happened, redacted; never the token or a phone number
     * @param retryAfter delay hint, {@code null} for none
     * @param healthDown whether the provider should report DOWN until a later success
     */
    record MetaFailure(FailureType type, String errorCode, String message, Duration retryAfter, boolean healthDown) {

        static MetaFailure of(FailureType type, String errorCode, String message) {
            return new MetaFailure(type, errorCode, message, null, false);
        }
    }

    private MetaErrorClassifier() {
    }

    /**
     * Classify a non-2xx answer.
     *
     * @param response  the answer
     * @param pairFloor least retry delay for code 131056
     * @param clock     the clock an HTTP-date Retry-After is measured against
     * @return the failure
     */
    static MetaFailure classify(MetaHttpResponse response, Duration pairFloor, Clock clock) {
        int status = response.statusCode();
        JsonNode error = MetaJson.readObject(response.body()).map(body -> body.get("error")).orElse(null);
        Integer code = error != null && error.path("code").isInt() ? error.path("code").intValue() : null;
        String message = describe(status, code, error);
        Duration retryAfter = FailureTypes.parseRetryAfter(response.header("Retry-After"), clock).orElse(null);

        if (code != null) {
            MetaFailure byCode = byCode(code, message, retryAfter, pairFloor);
            if (byCode != null) {
                return byCode;
            }
        }
        if (status == HTTP_TOO_MANY_REQUESTS) {
            return new MetaFailure(FailureType.TRANSIENT, MetaErrorCodes.THROTTLED, message, retryAfter, false);
        }
        if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) {
            return new MetaFailure(FailureType.PERMANENT, MetaErrorCodes.CREDENTIAL_REJECTED, message, null, true);
        }
        if (status >= HTTP_SERVER_ERROR || status == HTTP_REQUEST_TIMEOUT) {
            return new MetaFailure(FailureType.TRANSIENT, MetaErrorCodes.PROVIDER_UNAVAILABLE, message,
                    retryAfter, false);
        }
        return MetaFailure.of(FailureType.PERMANENT, MetaErrorCodes.REJECTED, message);
    }

    private static MetaFailure byCode(int code, String message, Duration retryAfter, Duration pairFloor) {
        if (code == CODE_PAIR_RATE_LIMIT) {
            Duration wait = retryAfter == null || retryAfter.compareTo(pairFloor) < 0 ? pairFloor : retryAfter;
            return new MetaFailure(FailureType.TRANSIENT, MetaErrorCodes.PAIR_RATE_LIMITED, message, wait, false);
        }
        if (THROTTLE_CODES.contains(code)) {
            return new MetaFailure(FailureType.TRANSIENT, MetaErrorCodes.THROTTLED, message, retryAfter, false);
        }
        if (ACCOUNT_CODES.contains(code)) {
            return new MetaFailure(FailureType.PERMANENT, MetaErrorCodes.ACCOUNT_RESTRICTED, message, null, true);
        }
        if (code == CODE_REENGAGEMENT) {
            return MetaFailure.of(FailureType.PERMANENT, MetaErrorCodes.REENGAGEMENT_REQUIRED, message);
        }
        if (code == CODE_UNDELIVERABLE) {
            return MetaFailure.of(FailureType.PERMANENT, MetaErrorCodes.RECIPIENT_UNDELIVERABLE, message);
        }
        if (code >= TEMPLATE_CODE_MIN && code <= TEMPLATE_CODE_MAX) {
            return MetaFailure.of(FailureType.PERMANENT, MetaErrorCodes.TEMPLATE_REJECTED, message);
        }
        if (CREDENTIAL_CODES.contains(code)) {
            return new MetaFailure(FailureType.PERMANENT, MetaErrorCodes.CREDENTIAL_REJECTED, message, null, true);
        }
        if (UNAVAILABLE_CODES.contains(code)) {
            return new MetaFailure(FailureType.TRANSIENT, MetaErrorCodes.PROVIDER_UNAVAILABLE, message,
                    retryAfter, false);
        }
        return null;
    }

    /**
     * Classify a transport failure: nothing sent is {@code CONNECT_FAILED},
     * anything after the request went out is {@code NO_CONFIRMATION}.
     */
    static MetaFailure transport(MetaTransportException e) {
        String cause = e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName();
        return e.requestSent()
                ? MetaFailure.of(FailureType.AMBIGUOUS, MetaErrorCodes.NO_CONFIRMATION,
                "No answer from Meta after the request was sent (" + cause + "); it may have been accepted")
                : MetaFailure.of(FailureType.TRANSIENT, MetaErrorCodes.CONNECT_FAILED,
                "Meta cannot be reached (" + cause + ")");
    }

    /** A 2xx answer without {@code messages[0].id}: Meta may have accepted the message. */
    static MetaFailure noMessageId(int status) {
        return MetaFailure.of(FailureType.AMBIGUOUS, MetaErrorCodes.NO_CONFIRMATION,
                "Meta answered HTTP " + status + " without a message id; it may have been accepted");
    }

    private static String describe(int status, Integer code, JsonNode error) {
        StringBuilder out = new StringBuilder("Meta answered HTTP ").append(status);
        if (code != null) {
            out.append(" (code ").append(code);
            if (error.path("error_subcode").isInt()) {
                out.append(", subcode ").append(error.path("error_subcode").intValue());
            }
            out.append(')');
        }
        String detail = error == null ? null : MetaJson.text(error.path("error_data"), "details");
        if (detail == null || detail.isBlank()) {
            detail = error == null ? null : MetaJson.text(error, "message");
        }
        if (detail != null && !detail.isBlank()) {
            String clean = PiiMasking.redact(detail.replaceAll("\\p{Cntrl}", " "));
            out.append(": ").append(clean.length() > MAX_DETAIL_LENGTH
                    ? clean.substring(0, MAX_DETAIL_LENGTH) + "..."
                    : clean);
        }
        return out.toString();
    }
}
