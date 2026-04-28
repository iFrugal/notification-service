package com.lazydevs.notification.api.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the shared {@link FailureTypes} classifier helper.
 *
 * <p>Covers the HTTP-status mapping (5xx, 408, 425, 429 → TRANSIENT;
 * other 4xx → PERMANENT; 1xx-3xx → UNKNOWN) and the I/O cause-chain
 * walk used by every provider.
 */
class FailureTypesTest {

    @Test
    void httpStatus_5xx_isTransient() {
        assertThat(FailureTypes.fromHttpStatus(500)).isEqualTo(FailureType.TRANSIENT);
        assertThat(FailureTypes.fromHttpStatus(502)).isEqualTo(FailureType.TRANSIENT);
        assertThat(FailureTypes.fromHttpStatus(503)).isEqualTo(FailureType.TRANSIENT);
        assertThat(FailureTypes.fromHttpStatus(504)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void httpStatus_429_408_425_areTransient() {
        // The retry-friendly 4xx subset.
        assertThat(FailureTypes.fromHttpStatus(429)).isEqualTo(FailureType.TRANSIENT);
        assertThat(FailureTypes.fromHttpStatus(408)).isEqualTo(FailureType.TRANSIENT);
        assertThat(FailureTypes.fromHttpStatus(425)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void httpStatus_other4xx_isPermanent() {
        assertThat(FailureTypes.fromHttpStatus(400)).isEqualTo(FailureType.PERMANENT);
        assertThat(FailureTypes.fromHttpStatus(401)).isEqualTo(FailureType.PERMANENT);
        assertThat(FailureTypes.fromHttpStatus(403)).isEqualTo(FailureType.PERMANENT);
        assertThat(FailureTypes.fromHttpStatus(404)).isEqualTo(FailureType.PERMANENT);
        assertThat(FailureTypes.fromHttpStatus(422)).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void httpStatus_successCodes_areUnknown() {
        // A 2xx reaching this method is a programming error (caller
        // should check success first); we don't throw, just return
        // UNKNOWN and defer to the predicate.
        assertThat(FailureTypes.fromHttpStatus(200)).isEqualTo(FailureType.UNKNOWN);
        assertThat(FailureTypes.fromHttpStatus(204)).isEqualTo(FailureType.UNKNOWN);
        assertThat(FailureTypes.fromHttpStatus(301)).isEqualTo(FailureType.UNKNOWN);
        assertThat(FailureTypes.fromHttpStatus(100)).isEqualTo(FailureType.UNKNOWN);
    }

    @Test
    void exception_directIOException_isTransient() {
        assertThat(FailureTypes.fromException(new IOException("connection refused")))
                .isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void exception_socketTimeout_isTransient() {
        // SocketTimeoutException extends IOException — common case.
        assertThat(FailureTypes.fromException(new SocketTimeoutException("read timeout")))
                .isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void exception_ioInCauseChain_isTransient() {
        // Provider SDKs often wrap the underlying network error.
        RuntimeException wrapped = new RuntimeException(
                "send failed",
                new RuntimeException("inner",
                        new IOException("broken pipe")));
        assertThat(FailureTypes.fromException(wrapped)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void exception_noIOInChain_isUnknown() {
        // No I/O signal anywhere — classifier defers.
        IllegalArgumentException bad = new IllegalArgumentException("bad input");
        assertThat(FailureTypes.fromException(bad)).isEqualTo(FailureType.UNKNOWN);
    }

    @Test
    void exception_null_isUnknown() {
        // Defensive: null in, UNKNOWN out (don't throw).
        assertThat(FailureTypes.fromException(null)).isEqualTo(FailureType.UNKNOWN);
    }
}
