package com.lazydevs.notification.api.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SendResultRetryAfterTest {

    @Test
    void readsIsoDurationsAndWholeSeconds() {
        assertThat(withHint("PT30S").retryAfter()).contains(Duration.ofSeconds(30));
        assertThat(withHint("PT0.25S").retryAfter()).contains(Duration.ofMillis(250));
        assertThat(withHint("30").retryAfter()).contains(Duration.ofSeconds(30));
        assertThat(withHint(" 45 ").retryAfter()).contains(Duration.ofSeconds(45));
        assertThat(withHint(30).retryAfter()).contains(Duration.ofSeconds(30));
        assertThat(withHint(30L).retryAfter()).contains(Duration.ofSeconds(30));
        assertThat(withHint(Duration.ofMillis(1500)).retryAfter()).contains(Duration.ofMillis(1500));
    }

    @Test
    void absentNegativeOrMalformedHintsAreEmpty() {
        assertThat(SendResult.failure("E", "m").retryAfter()).isEmpty();
        assertThat(withHint(null).retryAfter()).isEmpty();
        assertThat(withHint("soon").retryAfter()).isEmpty();
        assertThat(withHint("").retryAfter()).isEmpty();
        assertThat(withHint("-5").retryAfter()).isEmpty();
        assertThat(withHint("PT-1S").retryAfter()).isEmpty();
        assertThat(withHint(-5).retryAfter()).isEmpty();
        assertThat(withHint(1.5).retryAfter()).isEmpty();
        assertThat(withHint("99999999999999999999").retryAfter()).isEmpty();
        assertThat(withHint(Boolean.TRUE).retryAfter()).isEmpty();
    }

    private static SendResult withHint(Object hint) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(SendResult.RETRY_AFTER_METADATA_KEY, hint);
        return new SendResult(false, null, "THROTTLED", "slow down", FailureType.TRANSIENT, Instant.now(), metadata);
    }
}
