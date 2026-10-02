package com.lazydevs.notification.api.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link SendResult#withRetryAfter(Duration)} and the metadata failure factory (since 1.2.0). */
class SendResultFactoriesTest {

    @Test
    void withRetryAfter_addsTheHint_andKeepsEverythingElse() {
        Instant at = Instant.parse("2026-10-02T10:00:00Z");
        SendResult original = new SendResult(false, "m-1", "THROTTLED", "slow down",
                FailureType.TRANSIENT, at, Map.of("region", "eu"));

        SendResult hinted = original.withRetryAfter(Duration.ofSeconds(30));

        assertThat(hinted.retryAfter()).contains(Duration.ofSeconds(30));
        assertThat(hinted.providerMetadata())
                .containsEntry("region", "eu")
                .containsEntry(SendResult.RETRY_AFTER_METADATA_KEY, "PT30S");
        assertThat(hinted.messageId()).isEqualTo("m-1");
        assertThat(hinted.errorCode()).isEqualTo("THROTTLED");
        assertThat(hinted.errorMessage()).isEqualTo("slow down");
        assertThat(hinted.failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(hinted.timestamp()).isEqualTo(at);
        assertThat(original.retryAfter()).isEmpty();
    }

    @Test
    void withRetryAfter_keepsSubSecondPrecision() {
        SendResult hinted = SendResult.failure("E", "m", FailureType.TRANSIENT)
                .withRetryAfter(Duration.ofMillis(1500));

        assertThat(hinted.retryAfter()).contains(Duration.ofMillis(1500));
    }

    @Test
    void withRetryAfter_nullOrNegative_removesTheHint() {
        SendResult hinted = SendResult.failure("E", "m", FailureType.TRANSIENT)
                .withRetryAfter(Duration.ofSeconds(5));

        assertThat(hinted.withRetryAfter(null).retryAfter()).isEmpty();
        assertThat(hinted.withRetryAfter(null).providerMetadata()).isNull();
        assertThat(hinted.withRetryAfter(Duration.ofSeconds(-1)).retryAfter()).isEmpty();
    }

    @Test
    void failure_withMessageIdAndMetadata_keepsBoth() {
        SendResult result = SendResult.failure("FCM_UNAVAILABLE", "busy", FailureType.TRANSIENT, "fcm-local:1",
                Map.of(SendResult.RETRY_AFTER_METADATA_KEY, "12"));

        assertThat(result.success()).isFalse();
        assertThat(result.messageId()).isEqualTo("fcm-local:1");
        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(result.retryAfter()).contains(Duration.ofSeconds(12));
    }

    @Test
    void failure_withNullTypeAndMetadata_isUnknownWithoutMetadata() {
        SendResult result = SendResult.failure("E", "m", null, null, null);

        assertThat(result.failureType()).isEqualTo(FailureType.UNKNOWN);
        assertThat(result.messageId()).isNull();
        assertThat(result.providerMetadata()).isNull();
    }
}
