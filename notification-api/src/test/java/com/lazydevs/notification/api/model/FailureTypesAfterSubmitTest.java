package com.lazydevs.notification.api.model;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FailureTypes#fromExceptionAfterSubmit(Throwable)} and
 * {@link FailureTypes#parseRetryAfter(String, Clock)} (since 1.2.0).
 */
class FailureTypesAfterSubmitTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void connectFailures_areTransient_becauseNothingWasSent() {
        assertThat(FailureTypes.fromExceptionAfterSubmit(new ConnectException("refused")))
                .isEqualTo(FailureType.TRANSIENT);
        assertThat(FailureTypes.fromExceptionAfterSubmit(new UnknownHostException("api.example.com")))
                .isEqualTo(FailureType.TRANSIENT);
        assertThat(FailureTypes.fromExceptionAfterSubmit(new NoRouteToHostException("no route")))
                .isEqualTo(FailureType.TRANSIENT);
        assertThat(FailureTypes.fromExceptionAfterSubmit(new HttpConnectTimeoutException("connect timed out")))
                .isEqualTo(FailureType.TRANSIENT);
        assertThat(FailureTypes.fromExceptionAfterSubmit(new SSLHandshakeException("handshake")))
                .isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void connectTimeoutsReportedAsSocketTimeouts_areTransient() {
        // The JDK's own connect timeout, and an HTTP client's (Apache HttpClient 5
        // makes ConnectTimeoutException a SocketTimeoutException).
        assertThat(FailureTypes.fromExceptionAfterSubmit(new SocketTimeoutException("Connect timed out")))
                .isEqualTo(FailureType.TRANSIENT);
        assertThat(FailureTypes.fromExceptionAfterSubmit(new RuntimeException(
                new ConnectTimeoutException("Connect to api.example.com:443 timed out"))))
                .isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void timeoutsAndOtherIoFailures_areAmbiguous_becauseTheRequestMayHaveArrived() {
        assertThat(FailureTypes.fromExceptionAfterSubmit(new SocketTimeoutException("read timed out")))
                .isEqualTo(FailureType.AMBIGUOUS);
        assertThat(FailureTypes.fromExceptionAfterSubmit(new HttpTimeoutException("request timed out")))
                .isEqualTo(FailureType.AMBIGUOUS);
        assertThat(FailureTypes.fromExceptionAfterSubmit(new SocketException("connection reset")))
                .isEqualTo(FailureType.AMBIGUOUS);
        assertThat(FailureTypes.fromExceptionAfterSubmit(new SSLException("record overflow")))
                .isEqualTo(FailureType.AMBIGUOUS);
        assertThat(FailureTypes.fromExceptionAfterSubmit(new IOException("broken pipe")))
                .isEqualTo(FailureType.AMBIGUOUS);
    }

    @Test
    void causeChainIsWalked_andAConnectFailureAnywhereWins() {
        RuntimeException wrappedTimeout = new RuntimeException("send failed",
                new UncheckedIOException(new SocketTimeoutException("read timed out")));
        assertThat(FailureTypes.fromExceptionAfterSubmit(wrappedTimeout)).isEqualTo(FailureType.AMBIGUOUS);

        IOException ioAroundConnect = new IOException("wrapper", new ConnectException("refused"));
        assertThat(FailureTypes.fromExceptionAfterSubmit(new RuntimeException(ioAroundConnect)))
                .isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void nonIoFailuresAndNull_areUnknown() {
        assertThat(FailureTypes.fromExceptionAfterSubmit(new IllegalStateException("bug")))
                .isEqualTo(FailureType.UNKNOWN);
        assertThat(FailureTypes.fromExceptionAfterSubmit(null)).isEqualTo(FailureType.UNKNOWN);
    }

    @Test
    void fromException_isUnchanged_andStillCallsEveryIoFailureTransient() {
        assertThat(FailureTypes.fromException(new SocketTimeoutException("read timed out")))
                .isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void parseRetryAfter_readsDeltaSecondsAndHttpDates() {
        assertThat(FailureTypes.parseRetryAfter("30", CLOCK)).contains(Duration.ofSeconds(30));
        assertThat(FailureTypes.parseRetryAfter(" 5 ", CLOCK)).contains(Duration.ofSeconds(5));
        assertThat(FailureTypes.parseRetryAfter("Fri, 02 Oct 2026 10:01:30 GMT", CLOCK))
                .contains(Duration.ofSeconds(90));
    }

    @Test
    void parseRetryAfter_isEmptyForAbsentMalformedPastAndZeroValues() {
        assertThat(FailureTypes.parseRetryAfter(null, CLOCK)).isEmpty();
        assertThat(FailureTypes.parseRetryAfter("  ", CLOCK)).isEmpty();
        assertThat(FailureTypes.parseRetryAfter("soon", CLOCK)).isEmpty();
        assertThat(FailureTypes.parseRetryAfter("-5", CLOCK)).isEmpty();
        assertThat(FailureTypes.parseRetryAfter("0", CLOCK)).isEmpty();
        assertThat(FailureTypes.parseRetryAfter("1.5", CLOCK)).isEmpty();
        assertThat(FailureTypes.parseRetryAfter("99999999999999999999", CLOCK)).isEmpty();
        assertThat(FailureTypes.parseRetryAfter("Fri, 02 Oct 2026 09:59:00 GMT", CLOCK)).isEmpty();
        assertThat(FailureTypes.parseRetryAfter("Fri, 02 Oct 2026 10:00:00 GMT", CLOCK)).isEmpty();
    }

    /** Stand-in with the simple name HTTP clients use for a connect timeout. */
    private static final class ConnectTimeoutException extends SocketTimeoutException {
        ConnectTimeoutException(String message) {
            super(message);
        }
    }
}
