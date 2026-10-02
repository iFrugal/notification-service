package com.lazydevs.notification.channel.sms.twilio;

import com.lazydevs.notification.api.model.FailureType;
import com.twilio.exception.ApiException;
import com.twilio.exception.AuthenticationException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Twilio SMS provider's failure classifier.
 *
 * <p>Twilio's {@code ApiException} carries the upstream HTTP status
 * code, so most of the work is delegated to
 * {@link com.lazydevs.notification.api.model.FailureTypes#fromHttpStatus}.
 * These tests verify the bridge between Twilio's exception API and our
 * uniform classifier shape.
 */
class TwilioFailureClassifierTest {

    @Test
    void authenticationException_isPermanent() {
        // Wrong account-sid / auth-token — config issue.
        Throwable t = new AuthenticationException("bad creds");
        assertThat(TwilioSmsProvider.classifyTwilio(t)).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void apiException_429_isTransient() {
        // Twilio's ApiException 8-arg constructor:
        // (message, code, moreInfo, status, httpStatusCode, params,
        // userError, cause). 'code' is the Twilio-specific error code;
        // 'status' is the HTTP status (what getStatusCode() returns).
        ApiException e = new ApiException("rate limited", 20429, null, 429, null, null, null, null);
        assertThat(TwilioSmsProvider.classifyTwilio(e)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void apiException_5xx_isTransient() {
        ApiException e = new ApiException("upstream err", 503);
        assertThat(TwilioSmsProvider.classifyTwilio(e)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void apiException_4xxOther_isPermanent() {
        // Twilio's "21211 Invalid To Number" comes back as HTTP 400 —
        // the status-code mapping catches it without needing a Twilio
        // error-code lookup table.
        ApiException e = new ApiException("Invalid 'To' Phone Number", 21211, null, 400, null, null, null, null);
        assertThat(TwilioSmsProvider.classifyTwilio(e)).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void apiException_nullStatus_isTransient() {
        // No status means the SDK didn't get a response — likely a
        // network failure pre-response. Treat as transient.
        ApiException e = new ApiException("connection failed");
        // single-arg constructor leaves status null
        assertThat(TwilioSmsProvider.classifyTwilio(e)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void apiException_nullStatus_causedByAReadTimeout_isAmbiguous() {
        // NetworkHttpClient wraps the IOException as ApiException(message, cause).
        ApiException e = new ApiException("Read timed out", new SocketTimeoutException("Read timed out"));
        assertThat(TwilioSmsProvider.classifyTwilio(e)).isEqualTo(FailureType.AMBIGUOUS);
    }

    @Test
    void apiException_nullStatus_causedByAConnectFailure_isTransient() {
        ApiException refused = new ApiException("Connection refused", new ConnectException("Connection refused"));
        // Apache HttpClient 5, which the Twilio SDK uses, reports a connect
        // timeout as a SocketTimeoutException subclass.
        ApiException connectTimeout = new ApiException("Connect to api.twilio.com timed out",
                new ConnectTimeoutException("Connect to api.twilio.com timed out"));
        assertThat(TwilioSmsProvider.classifyTwilio(refused)).isEqualTo(FailureType.TRANSIENT);
        assertThat(TwilioSmsProvider.classifyTwilio(connectTimeout)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void rawIOException_isTransient_viaFallback() {
        // SDK sometimes leaks a raw IOException through.
        Throwable t = new IOException("connection reset");
        assertThat(TwilioSmsProvider.classifyTwilio(t)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void unrelatedException_isUnknown() {
        Throwable t = new IllegalArgumentException("weird");
        assertThat(TwilioSmsProvider.classifyTwilio(t)).isEqualTo(FailureType.UNKNOWN);
    }
}
