package com.lazydevs.notification.api.idempotency;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyStoreStoredFormTest {

    @Test
    void failedAndRejectedResponsesKeepOnlyAScrubbedErrorMessage() {
        for (NotificationStatus status : new NotificationStatus[] {NotificationStatus.FAILED, NotificationStatus.REJECTED}) {
            NotificationResponse response = response(status, "ACS_400",
                    "Recipient john.doe@example.com (+15551234567) is suppressed")
                    .withFailureType(FailureType.PERMANENT);

            NotificationResponse stored = IdempotencyStore.storedForm(response);

            assertThat(stored.errorMessage()).as(status.name())
                    .isEqualTo("Recipient [email] ([phone]) is suppressed");
            assertThat(stored.failureType()).isEqualTo(FailureType.PERMANENT);
            assertThat(stored).usingRecursiveComparison().ignoringFields("errorMessage").isEqualTo(response);
        }
    }

    @Test
    void aScrubberReturningNullDropsTheMessageAsIn120() {
        NotificationResponse response = response(NotificationStatus.FAILED, "E", "anything");

        assertThat(IdempotencyStore.storedForm(response, message -> null).errorMessage()).isNull();
    }

    @Test
    void replayableResponsesAreStoredUnchanged() {
        NotificationResponse sent = response(NotificationStatus.SENT, null, null);

        assertThat(IdempotencyStore.storedForm(sent)).isSameAs(sent);
        assertThat(IdempotencyStore.storedForm(null)).isNull();
        NotificationResponse failedWithoutMessage = response(NotificationStatus.FAILED, "E", null);
        assertThat(IdempotencyStore.storedForm(failedWithoutMessage)).isSameAs(failedWithoutMessage);
    }

    @Test
    void defaultSpiMethodsKeepThe120Behaviour() {
        IdempotencyStore minimal = new IdempotencyStore() {
            @Override
            public java.util.Optional<IdempotencyRecord> findExisting(IdempotencyKey key) {
                return java.util.Optional.empty();
            }

            @Override
            public boolean markInProgress(IdempotencyKey key, String notificationId) {
                return true;
            }

            @Override
            public void markComplete(IdempotencyKey key, NotificationResponse response) {
                // not needed
            }
        };
        IdempotencyKey key = new IdempotencyKey("t", null, "k");

        assertThat(minimal.markInProgress(key, "r1", "abc")).isTrue();
        assertThat(minimal.settleInProgress(key, "r1", response(NotificationStatus.FAILED, "E", null))).isFalse();
        assertThat(new IdempotencyRecord("r1", IdempotencyStatus.COMPLETE, null, Instant.now()).fingerprint())
                .isNull();
    }

    private static NotificationResponse response(NotificationStatus status, String code, String message) {
        Instant now = Instant.now();
        return new NotificationResponse("req-1", "corr-1", "acme", "billing", Channel.EMAIL, "acs",
                status, "op-1", code, message, now, now, null, null);
    }
}
