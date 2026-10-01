package com.lazydevs.notification.api.model;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 1.1.1 failure factories that keep a provider message id, next to the
 * existing ones that do not.
 */
class FailureFactoriesTest {

    private static NotificationRequest request() {
        return NotificationRequest.builder()
                .requestId("req-1")
                .tenantId("acme")
                .channel(Channel.EMAIL)
                .build();
    }

    @Test
    void sendResultFailure_withMessageId_keepsIt() {
        SendResult result = SendResult.failure("ACS_X", "boom", FailureType.TRANSIENT, "op-1");

        assertThat(result.success()).isFalse();
        assertThat(result.messageId()).isEqualTo("op-1");
        assertThat(result.errorCode()).isEqualTo("ACS_X");
        assertThat(result.errorMessage()).isEqualTo("boom");
        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(result.timestamp()).isNotNull();
    }

    @Test
    void sendResultFailure_nullType_isUnknown_andThreeArgFormHasNoId() {
        assertThat(SendResult.failure("X", "y", null, "op").failureType()).isEqualTo(FailureType.UNKNOWN);
        assertThat(SendResult.failure("X", "y", FailureType.PERMANENT).messageId()).isNull();
    }

    @Test
    void notificationResponseFailed_withProviderMessageId_keepsIt() {
        Instant received = Instant.parse("2026-10-01T10:00:00Z");

        NotificationResponse response = NotificationResponse.failed(request(), "acs", "op-1",
                "ACS_X", "boom", received);

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(response.provider()).isEqualTo("acs");
        assertThat(response.providerMessageId()).isEqualTo("op-1");
        assertThat(response.errorCode()).isEqualTo("ACS_X");
        assertThat(response.errorMessage()).isEqualTo("boom");
        assertThat(response.receivedAt()).isEqualTo(received);
        assertThat(response.requestId()).isEqualTo("req-1");
    }

    @Test
    void notificationResponseFailed_legacyForm_hasNoProviderMessageId() {
        NotificationResponse response = NotificationResponse.failed(request(), "acs", "ACS_X", "boom", Instant.now());

        assertThat(response.providerMessageId()).isNull();
        assertThat(response.errorCode()).isEqualTo("ACS_X");
    }
}
