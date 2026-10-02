package com.lazydevs.notification.api.retry;

import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.SendResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPredicateDefaultTest {

    @Test
    void retriesTransientUnknownAndUnclassifiedFailures() {
        assertThat(RetryPredicate.DEFAULT.shouldRetry(failure(FailureType.TRANSIENT), 1)).isTrue();
        assertThat(RetryPredicate.DEFAULT.shouldRetry(failure(FailureType.UNKNOWN), 1)).isTrue();
        // A SendResult built through the canonical constructor can carry a null type.
        assertThat(RetryPredicate.DEFAULT.shouldRetry(
                new SendResult(false, null, "E", "m", null, Instant.now(), null), 1)).isTrue();
    }

    @Test
    void doesNotRetryPermanentOrAmbiguousFailures() {
        assertThat(RetryPredicate.DEFAULT.shouldRetry(failure(FailureType.PERMANENT), 1)).isFalse();
        assertThat(RetryPredicate.DEFAULT.shouldRetry(failure(FailureType.AMBIGUOUS), 1)).isFalse();
    }

    @Test
    void doesNotRetrySuccessesOrNull() {
        assertThat(RetryPredicate.DEFAULT.shouldRetry(SendResult.success("m-1"), 1)).isFalse();
        assertThat(RetryPredicate.DEFAULT.shouldRetry(null, 1)).isFalse();
    }

    private static SendResult failure(FailureType type) {
        return SendResult.failure("E", "m", type);
    }
}
