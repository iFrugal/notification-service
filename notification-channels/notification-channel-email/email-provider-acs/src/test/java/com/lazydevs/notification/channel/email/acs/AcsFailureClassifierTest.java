package com.lazydevs.notification.channel.email.acs;

import com.azure.core.exception.ClientAuthenticationException;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpResponse;
import com.azure.identity.CredentialUnavailableException;
import com.lazydevs.notification.api.model.FailureType;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
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
    void unauthorized401_andForbidden403_areTransient_withCredentialHint() {
        assertThat(AcsEmailProvider.classifyAcs(httpError(401))).isEqualTo(FailureType.TRANSIENT);
        assertThat(AcsEmailProvider.classifyAcs(httpError(403))).isEqualTo(FailureType.TRANSIENT);
        assertThat(AcsEmailProvider.describe(httpError(401))).contains("check the ACS credentials");
        assertThat(AcsEmailProvider.describe(httpError(400))).doesNotContain("credentials");
    }

    @Test
    void credentialFailures_areTransient_evenWithoutAResponse() {
        assertThat(AcsEmailProvider.classifyAcs(new ClientAuthenticationException("no token", null)))
                .isEqualTo(FailureType.TRANSIENT);
        assertThat(AcsEmailProvider.classifyAcs(new CredentialUnavailableException("no identity")))
                .isEqualTo(FailureType.TRANSIENT);
        assertThat(AcsEmailProvider.classifyAcs(
                new IllegalStateException(new CredentialUnavailableException("wrapped"))))
                .isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void permanent4xx_isPermanent() {
        assertThat(AcsEmailProvider.classifyAcs(httpError(404))).isEqualTo(FailureType.PERMANENT);
        assertThat(AcsEmailProvider.classifyAcs(httpError(422))).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void timeoutException_isAmbiguous_alsoWhenWrappedByReactor() {
        assertThat(AcsEmailProvider.classifyAcs(new TimeoutException("slow")))
                .isEqualTo(FailureType.AMBIGUOUS);
        assertThat(AcsEmailProvider.classifyAcs(Exceptions.propagate(new TimeoutException("slow"))))
                .isEqualTo(FailureType.AMBIGUOUS);
    }

    @Test
    void readTimeoutsAndResets_areAmbiguous() {
        assertThat(AcsEmailProvider.classifyAcs(new HttpTimeoutException("request timed out")))
                .isEqualTo(FailureType.AMBIGUOUS);
        assertThat(AcsEmailProvider.classifyAcs(new UncheckedIOException(new IOException("Connection reset"))))
                .isEqualTo(FailureType.AMBIGUOUS);
        assertThat(AcsEmailProvider.classifyAcs(new UncheckedIOException(new SocketTimeoutException("Read timed out"))))
                .isEqualTo(FailureType.AMBIGUOUS);
    }

    @Test
    void connectFailures_areTransient() {
        assertThat(AcsEmailProvider.classifyAcs(new UncheckedIOException(new ConnectException("Connection refused"))))
                .isEqualTo(FailureType.TRANSIENT);
        assertThat(AcsEmailProvider.classifyAcs(new UncheckedIOException(new UnknownHostException("acs.invalid"))))
                .isEqualTo(FailureType.TRANSIENT);
        assertThat(AcsEmailProvider.classifyAcs(new HttpConnectTimeoutException("connect timed out")))
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
