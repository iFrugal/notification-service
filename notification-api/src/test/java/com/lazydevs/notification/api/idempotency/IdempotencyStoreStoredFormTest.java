package com.lazydevs.notification.api.idempotency;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.model.NotificationResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyStoreStoredFormTest {

    @Test
    void failedAndRejectedResponsesLoseOnlyTheirErrorMessage() {
        for (NotificationStatus status : new NotificationStatus[] {NotificationStatus.FAILED, NotificationStatus.REJECTED}) {
            NotificationResponse response = response(status, "ACS_400", "Recipient john.doe@example.com is suppressed");

            NotificationResponse stored = IdempotencyStore.storedForm(response);

            assertThat(stored.errorMessage()).as(status.name()).isNull();
            assertThat(stored).usingRecursiveComparison().ignoringFields("errorMessage").isEqualTo(response);
        }
    }

    @Test
    void replayableResponsesAreStoredUnchanged() {
        NotificationResponse sent = response(NotificationStatus.SENT, null, null);

        assertThat(IdempotencyStore.storedForm(sent)).isSameAs(sent);
        assertThat(IdempotencyStore.storedForm(null)).isNull();
    }

    private static NotificationResponse response(NotificationStatus status, String code, String message) {
        Instant now = Instant.now();
        return new NotificationResponse("req-1", "corr-1", "acme", "billing", Channel.EMAIL, "acs",
                status, "op-1", code, message, now, now, null, null);
    }
}
