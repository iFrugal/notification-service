package com.lazydevs.notification.channel.whatsapp.meta;

import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.channel.whatsapp.meta.MetaErrorClassifier.MetaFailure;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every row of the DD-35 classification table.
 */
class MetaErrorClassificationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Duration FLOOR = Duration.ofSeconds(6);

    private static MetaFailure classify(int status, Integer code, String retryAfter) {
        String body = code == null ? "{}" : MetaStubServer.graphError(code, "detail for " + code);
        Map<String, List<String>> headers = retryAfter == null ? Map.of() : Map.of("Retry-After", List.of(retryAfter));
        return MetaErrorClassifier.classify(
                new MetaHttpResponse(status, headers, body.getBytes(StandardCharsets.UTF_8)), FLOOR, CLOCK);
    }

    @ParameterizedTest(name = "HTTP {0} code {1} -> {2} {3} down={4}")
    @CsvSource(nullValues = "null", value = {
            // status, code, type, errorCode, healthDown
            "429, null,   TRANSIENT, THROTTLED, false",
            "400, 4,      TRANSIENT, THROTTLED, false",
            "400, 80007,  TRANSIENT, THROTTLED, false",
            "400, 130429, TRANSIENT, THROTTLED, false",
            "429, 131056, TRANSIENT, PAIR_RATE_LIMITED, false",
            "400, 131056, TRANSIENT, PAIR_RATE_LIMITED, false",
            "500, null,   TRANSIENT, PROVIDER_UNAVAILABLE, false",
            "503, null,   TRANSIENT, PROVIDER_UNAVAILABLE, false",
            "408, null,   TRANSIENT, PROVIDER_UNAVAILABLE, false",
            "400, 1,      TRANSIENT, PROVIDER_UNAVAILABLE, false",
            "400, 2,      TRANSIENT, PROVIDER_UNAVAILABLE, false",
            "500, 131000, TRANSIENT, PROVIDER_UNAVAILABLE, false",
            "400, 131016, TRANSIENT, PROVIDER_UNAVAILABLE, false",
            "400, 133004, TRANSIENT, PROVIDER_UNAVAILABLE, false",
            "401, null,   PERMANENT, CREDENTIAL_REJECTED, true",
            "403, null,   PERMANENT, CREDENTIAL_REJECTED, true",
            "401, 190,    PERMANENT, CREDENTIAL_REJECTED, true",
            "400, 10,     PERMANENT, CREDENTIAL_REJECTED, true",
            "403, 200,    PERMANENT, CREDENTIAL_REJECTED, true",
            "400, 131047, PERMANENT, REENGAGEMENT_REQUIRED, false",
            "400, 131026, PERMANENT, RECIPIENT_UNDELIVERABLE, false",
            "400, 132000, PERMANENT, TEMPLATE_REJECTED, false",
            "404, 132001, PERMANENT, TEMPLATE_REJECTED, false",
            "400, 132999, PERMANENT, TEMPLATE_REJECTED, false",
            "400, 368,    PERMANENT, ACCOUNT_RESTRICTED, true",
            "403, 131031, PERMANENT, ACCOUNT_RESTRICTED, true",
            "400, 131042, PERMANENT, ACCOUNT_RESTRICTED, true",
            "400, 100,    PERMANENT, REJECTED, false",
            "400, null,   PERMANENT, REJECTED, false",
            "404, null,   PERMANENT, REJECTED, false",
            "302, null,   PERMANENT, REJECTED, false",
            "400, 133000, PERMANENT, REJECTED, false"
    })
    void classificationTable(int status, Integer code, FailureType type, String errorCode, boolean healthDown) {
        MetaFailure failure = classify(status, code, null);

        assertThat(failure.type()).isEqualTo(type);
        assertThat(failure.errorCode()).isEqualTo(errorCode);
        assertThat(failure.healthDown()).isEqualTo(healthDown);
        assertThat(failure.message()).startsWith("Meta answered HTTP " + status);
        if (code != null) {
            assertThat(failure.message()).contains("(code " + code).contains("detail for " + code);
        }
    }

    @Test
    void throttledAndUnavailable_carryRetryAfter_secondsOrHttpDate() {
        assertThat(classify(429, null, "30").retryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(classify(400, 130429, "Thu, 01 Jan 2026 00:01:00 GMT").retryAfter())
                .isEqualTo(Duration.ofMinutes(1));
        assertThat(classify(503, null, "5").retryAfter()).isEqualTo(Duration.ofSeconds(5));
        assertThat(classify(429, null, null).retryAfter()).isNull();
        assertThat(classify(400, 132001, "30").retryAfter()).isNull();
    }

    @Test
    void pairRateLimit_waitsAtLeastTheFloor() {
        assertThat(classify(400, 131056, null).retryAfter()).isEqualTo(FLOOR);
        assertThat(classify(400, 131056, "2").retryAfter()).isEqualTo(FLOOR);
        assertThat(classify(400, 131056, "20").retryAfter()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void transportFailures_connectIsTransient_afterSendIsAmbiguous() {
        MetaFailure connect = MetaErrorClassifier.transport(
                MetaTransportException.notSent("refused", new ConnectException("refused")));
        MetaFailure timeout = MetaErrorClassifier.transport(
                MetaTransportException.afterSend("timeout", new HttpTimeoutException("timed out")));
        MetaFailure lost = MetaErrorClassifier.transport(
                MetaTransportException.afterSend("lost", new IOException("reset")));
        MetaFailure interrupted = MetaErrorClassifier.transport(MetaTransportException.afterSend("x", null));

        assertThat(connect.type()).isEqualTo(FailureType.TRANSIENT);
        assertThat(connect.errorCode()).isEqualTo(MetaErrorCodes.CONNECT_FAILED);
        for (MetaFailure ambiguous : List.of(timeout, lost, interrupted)) {
            assertThat(ambiguous.type()).isEqualTo(FailureType.AMBIGUOUS);
            assertThat(ambiguous.errorCode()).isEqualTo(MetaErrorCodes.NO_CONFIRMATION);
        }
    }

    @Test
    void successWithoutMessageId_isAmbiguous() {
        MetaFailure failure = MetaErrorClassifier.noMessageId(200);
        assertThat(failure.type()).isEqualTo(FailureType.AMBIGUOUS);
        assertThat(failure.errorCode()).isEqualTo(MetaErrorCodes.NO_CONFIRMATION);
    }

    @Test
    void errorDetail_isRedacted_truncated_andFreeOfControlCharacters() {
        String body = "{\"error\":{\"code\":131030,\"error_subcode\":2494010,"
                + "\"message\":\"Recipient +15550100001 not in allowed list\\nsecond line\"}}";
        MetaFailure failure = MetaErrorClassifier.classify(
                new MetaHttpResponse(400, Map.of(), body.getBytes(StandardCharsets.UTF_8)), FLOOR, CLOCK);

        assertThat(failure.message()).contains("subcode 2494010").doesNotContain("15550100001").doesNotContain("\n");
        String longBody = "{\"error\":{\"code\":100,\"message\":\"" + "x".repeat(1000) + "\"}}";
        assertThat(MetaErrorClassifier.classify(new MetaHttpResponse(400, Map.of(),
                longBody.getBytes(StandardCharsets.UTF_8)), FLOOR, CLOCK).message()).hasSizeLessThan(400);
    }

    @Test
    void nonJsonErrorBody_fallsBackToTheStatus() {
        MetaFailure failure = MetaErrorClassifier.classify(new MetaHttpResponse(502, Map.of(),
                "<html>bad gateway</html>".getBytes(StandardCharsets.UTF_8)), FLOOR, CLOCK);
        assertThat(failure.errorCode()).isEqualTo(MetaErrorCodes.PROVIDER_UNAVAILABLE);
        assertThat(failure.message()).isEqualTo("Meta answered HTTP 502");
    }
}
