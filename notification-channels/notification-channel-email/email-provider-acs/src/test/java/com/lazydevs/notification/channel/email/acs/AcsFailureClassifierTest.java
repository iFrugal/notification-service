package com.lazydevs.notification.channel.email.acs;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpResponse;
import com.lazydevs.notification.api.model.FailureType;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AcsEmailProvider#classifyAcs(Throwable)}.
 */
class AcsFailureClassifierTest {

    private static HttpResponseException httpError(int status) {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        return new HttpResponseException("HTTP " + status, response);
    }

    private static HttpResponseException httpError(int status, String retryAfter) {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        when(response.getHeaders()).thenReturn(new HttpHeaders().set(HttpHeaderName.RETRY_AFTER, retryAfter));
        return new HttpResponseException("HTTP " + status, response);
    }

    @Test
    void retryAfter_readsSecondsAndHttpDates() {
        Instant now = Instant.parse("2026-10-02T10:00:00Z");

        assertThat(AcsEmailProvider.retryAfter(httpError(429, "30"), now)).contains(Duration.ofSeconds(30));
        assertThat(AcsEmailProvider.retryAfter(httpError(429, " 5 "), now)).contains(Duration.ofSeconds(5));
        assertThat(AcsEmailProvider.retryAfter(httpError(503, "Fri, 02 Oct 2026 10:01:30 GMT"), now))
                .contains(Duration.ofSeconds(90));
    }

    @Test
    void retryAfter_ignoresMalformedPastAndZeroValues_andNonHttpErrors() {
        Instant now = Instant.parse("2026-10-02T10:00:00Z");

        assertThat(AcsEmailProvider.retryAfter(httpError(429, "soon"), now)).isEmpty();
        assertThat(AcsEmailProvider.retryAfter(httpError(429, "-5"), now)).isEmpty();
        assertThat(AcsEmailProvider.retryAfter(httpError(429, "0"), now)).isEmpty();
        assertThat(AcsEmailProvider.retryAfter(httpError(429, "99999999999999999999"), now)).isEmpty();
        assertThat(AcsEmailProvider.retryAfter(httpError(503, "Fri, 02 Oct 2026 09:59:00 GMT"), now)).isEmpty();
        assertThat(AcsEmailProvider.retryAfter(httpError(429), now)).isEmpty();
        assertThat(AcsEmailProvider.retryAfter(new TimeoutException("slow"), now)).isEmpty();
    }

    @Test
    void throttled429_isTransient() {
        assertThat(AcsEmailProvider.classifyAcs(httpError(429))).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void serviceUnavailable503_isTransient() {
        assertThat(AcsEmailProvider.classifyAcs(httpError(503))).isEqualTo(FailureType.TRANSIENT);
        assertThat(AcsEmailProvider.classifyAcs(httpError(500))).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void requestTimeout408_isTransient() {
        assertThat(AcsEmailProvider.classifyAcs(httpError(408))).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void badRequest400_isPermanent() {
        assertThat(AcsEmailProvider.classifyAcs(httpError(400))).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void unauthorized401_andForbidden403_arePermanent_withCredentialHint() {
        assertThat(AcsEmailProvider.classifyAcs(httpError(401))).isEqualTo(FailureType.PERMANENT);
        assertThat(AcsEmailProvider.classifyAcs(httpError(403))).isEqualTo(FailureType.PERMANENT);
        assertThat(AcsEmailProvider.describe(httpError(401))).contains("check the ACS credentials");
        assertThat(AcsEmailProvider.describe(httpError(400))).doesNotContain("credentials");
    }

    @Test
    void timeoutException_isTransient_alsoWhenWrappedByReactor() {
        assertThat(AcsEmailProvider.classifyAcs(new TimeoutException("slow")))
                .isEqualTo(FailureType.TRANSIENT);
        assertThat(AcsEmailProvider.classifyAcs(Exceptions.propagate(new TimeoutException("slow"))))
                .isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void httpTimeoutAndIoExceptions_areTransient() {
        assertThat(AcsEmailProvider.classifyAcs(new HttpTimeoutException("request timed out")))
                .isEqualTo(FailureType.TRANSIENT);
        assertThat(AcsEmailProvider.classifyAcs(new UncheckedIOException(new IOException("reset"))))
                .isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void unrelatedException_isUnknown() {
        assertThat(AcsEmailProvider.classifyAcs(new IllegalArgumentException("weird")))
                .isEqualTo(FailureType.UNKNOWN);
    }

    @Test
    void httpResponseExceptionWithoutResponse_isUnknown() {
        assertThat(AcsEmailProvider.classifyAcs(new HttpResponseException("no response", null)))
                .isEqualTo(FailureType.UNKNOWN);
    }
}
