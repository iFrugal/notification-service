package com.lazydevs.notification.channel.email.ses;

import com.lazydevs.notification.api.model.FailureType;
import org.junit.jupiter.api.Test;
import org.apache.hc.client5.http.ConnectTimeoutException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.model.AccountSuspendedException;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.MailFromDomainNotVerifiedException;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendingPausedException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the SES v2 provider's failure classifier.
 *
 * <p>The AWS SDK builds typed exceptions through their fluent builders
 * — {@code TypedException.builder().statusCode(500).build()} — which
 * is what we need to feed the classifier without hitting a real SES
 * endpoint.
 */
class SesFailureClassifierTest {

    @Test
    void sdkClientException_isTransient() {
        // Client-side failure without a timeout: nothing reached SES.
        Throwable t = SdkClientException.builder()
                .message("connection failed")
                .build();
        assertThat(SesEmailProvider.classifySes(t)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void apiCallTimeouts_areAmbiguous_becauseSesMayHaveAcceptedTheMessage() {
        assertThat(SesEmailProvider.classifySes(ApiCallTimeoutException.create(10_000)))
                .isEqualTo(FailureType.AMBIGUOUS);
        assertThat(SesEmailProvider.classifySes(ApiCallAttemptTimeoutException.create(5_000)))
                .isEqualTo(FailureType.AMBIGUOUS);
    }

    @Test
    void readTimeout_isAmbiguous() {
        Throwable t = SdkClientException.builder()
                .message("Unable to execute HTTP request: Read timed out")
                .cause(new SocketTimeoutException("Read timed out"))
                .build();
        assertThat(SesEmailProvider.classifySes(t)).isEqualTo(FailureType.AMBIGUOUS);
    }

    @Test
    void connectFailuresAndConnectTimeouts_stayTransient_becauseNothingWasSent() {
        Throwable refused = SdkClientException.builder()
                .message("Unable to execute HTTP request: Connection refused")
                .cause(new ConnectException("Connection refused"))
                .build();
        // The SDK's default sync HTTP client, Apache HttpClient 5, reports a
        // connect timeout as a SocketTimeoutException subclass.
        Throwable apacheConnectTimeout = SdkClientException.builder()
                .message("Unable to execute HTTP request: Connect to email.eu-west-1.amazonaws.com timed out")
                .cause(new ConnectTimeoutException("Connect to email.eu-west-1.amazonaws.com timed out"))
                .build();
        Throwable jdkConnectTimeout = SdkClientException.builder()
                .message("Unable to execute HTTP request: Connect timed out")
                .cause(new SocketTimeoutException("Connect timed out"))
                .build();

        assertThat(SesEmailProvider.classifySes(refused)).isEqualTo(FailureType.TRANSIENT);
        assertThat(SesEmailProvider.classifySes(apacheConnectTimeout)).isEqualTo(FailureType.TRANSIENT);
        assertThat(SesEmailProvider.classifySes(jdkConnectTimeout)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void accountSuspended_isPermanent() {
        Throwable t = AccountSuspendedException.builder().message("account suspended").build();
        assertThat(SesEmailProvider.classifySes(t)).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void sendingPaused_isPermanent() {
        Throwable t = SendingPausedException.builder().message("sending paused").build();
        assertThat(SesEmailProvider.classifySes(t)).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void mailFromDomainNotVerified_isPermanent() {
        Throwable t = MailFromDomainNotVerifiedException.builder()
                .message("verify your domain").build();
        assertThat(SesEmailProvider.classifySes(t)).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void messageRejected_isPermanent() {
        Throwable t = MessageRejectedException.builder().message("content rejected").build();
        assertThat(SesEmailProvider.classifySes(t)).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void badRequest_isPermanent() {
        Throwable t = BadRequestException.builder().message("malformed").build();
        assertThat(SesEmailProvider.classifySes(t)).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void awsServiceException_5xx_isTransient() {
        Throwable t = AwsServiceException.builder()
                .message("internal server error")
                .statusCode(500)
                .build();
        assertThat(SesEmailProvider.classifySes(t)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void awsServiceException_429_isTransient() {
        // Throttling / SlowDown — caught by the HTTP-status branch
        // without needing a TooManyRequestsException special-case.
        Throwable t = AwsServiceException.builder()
                .message("throttled")
                .statusCode(429)
                .build();
        assertThat(SesEmailProvider.classifySes(t)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void awsServiceException_4xx_isPermanent() {
        Throwable t = AwsServiceException.builder()
                .message("forbidden")
                .statusCode(403)
                .build();
        assertThat(SesEmailProvider.classifySes(t)).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void plainIOException_isTransient_viaFallback() {
        // Something escaped the SDK with a raw IOException — fallback
        // path catches it.
        Throwable t = new IOException("connection reset");
        assertThat(SesEmailProvider.classifySes(t)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void unrelatedException_isUnknown() {
        Throwable t = new IllegalArgumentException("weird");
        assertThat(SesEmailProvider.classifySes(t)).isEqualTo(FailureType.UNKNOWN);
    }
}
