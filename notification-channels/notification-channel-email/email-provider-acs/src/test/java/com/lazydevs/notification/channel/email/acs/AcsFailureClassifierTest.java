package com.lazydevs.notification.channel.email.acs;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpResponse;
import com.lazydevs.notification.api.model.FailureType;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpTimeoutException;
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
