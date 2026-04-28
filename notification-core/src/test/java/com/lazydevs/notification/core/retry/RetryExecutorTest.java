package com.lazydevs.notification.core.retry;

import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.retry.RetryPredicate;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.RetryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the DD-13 {@link RetryExecutor}.
 *
 * <p>Tests use very short backoff windows (1ms / 2ms) so they finish
 * fast — the algorithm is exercised, the wall-clock waiting is not.
 */
class RetryExecutorTest {

    private NotificationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.getRetry().setEnabled(true);
        // Default tight retry config — individual tests override.
        properties.getRetry().setMaxAttempts(3);
        properties.getRetry().setInitialDelay(Duration.ofMillis(1));
        properties.getRetry().setMultiplier(2.0);
        properties.getRetry().setMaxDelay(Duration.ofMillis(10));
        properties.getRetry().setJitter(0.0);  // deterministic for tests
    }

    @Test
    void successOnFirstAttempt_returnsImmediately_attemptsEqualsOne() {
        RetryExecutor executor = new RetryExecutor(properties, Optional.empty());
        AtomicInteger calls = new AtomicInteger();

        RetryExecutor.Outcome outcome = executor.execute(() -> {
            calls.incrementAndGet();
            return SendResult.success("msg-1");
        });

        assertThat(outcome.result().success()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void transientFailure_thenSuccess_attemptsCounted() {
        RetryExecutor executor = new RetryExecutor(properties, Optional.empty());
        AtomicInteger calls = new AtomicInteger();

        RetryExecutor.Outcome outcome = executor.execute(() -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return SendResult.failure("THROTTLED", "rate limited", FailureType.TRANSIENT);
            }
            return SendResult.success("msg-2");
        });

        assertThat(outcome.result().success()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(2);
    }

    @Test
    void permanentFailure_doesNotRetry() {
        RetryExecutor executor = new RetryExecutor(properties, Optional.empty());
        AtomicInteger calls = new AtomicInteger();

        RetryExecutor.Outcome outcome = executor.execute(() -> {
            calls.incrementAndGet();
            return SendResult.failure("BAD_RECIPIENT", "Invalid email", FailureType.PERMANENT);
        });

        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void unknownFailure_isRetried_byDefaultPredicate() {
        // FailureType.UNKNOWN is the default for SendResult.failure(...)
        // factories — operators upgrading providers haven't classified
        // yet. Default predicate retries these.
        RetryExecutor executor = new RetryExecutor(properties, Optional.empty());
        AtomicInteger calls = new AtomicInteger();

        executor.execute(() -> {
            calls.incrementAndGet();
            return SendResult.failure("UNKNOWN", "some weird error");
        });

        // 3 max-attempts → all 3 attempted because UNKNOWN is retried.
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void maxAttemptsExhausted_returnsLastFailure() {
        RetryExecutor executor = new RetryExecutor(properties, Optional.empty());
        AtomicInteger calls = new AtomicInteger();

        RetryExecutor.Outcome outcome = executor.execute(() -> {
            calls.incrementAndGet();
            return SendResult.failure("THROTTLED", "still rate limited", FailureType.TRANSIENT);
        });

        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(3);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void thrownException_isWrappedAsFailure_andRetried() {
        // A provider that throws (instead of returning a failed
        // SendResult) shouldn't break the retry loop — wrap the
        // exception, treat as UNKNOWN, retry per the default predicate.
        RetryExecutor executor = new RetryExecutor(properties, Optional.empty());
        AtomicInteger calls = new AtomicInteger();

        RetryExecutor.Outcome outcome = executor.execute(() -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
                throw new RuntimeException("provider boom");
            }
            return SendResult.success("msg-recover");
        });

        assertThat(outcome.result().success()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(3);
    }

    @Test
    void customPredicate_overridesDefault() {
        // Operator-supplied predicate that opts OUT of retrying UNKNOWN —
        // wants explicit-only TRANSIENT retries.
        RetryPredicate strict = (result, attempt) ->
                result.failureType() == FailureType.TRANSIENT;
        RetryExecutor executor = new RetryExecutor(properties, Optional.of(strict));
        AtomicInteger calls = new AtomicInteger();

        executor.execute(() -> {
            calls.incrementAndGet();
            return SendResult.failure("MYSTERY", "no idea");  // → UNKNOWN
        });

        assertThat(calls.get()).isEqualTo(1);  // not retried
    }

    @Test
    void backoffComputation_exponentialAndCapped() {
        // Verify the backoff schedule directly — easier than measuring
        // wall-clock sleep times.
        RetryProperties cfg = properties.getRetry();
        cfg.setInitialDelay(Duration.ofMillis(100));
        cfg.setMultiplier(2.0);
        cfg.setMaxDelay(Duration.ofMillis(500));
        cfg.setJitter(0.0);
        RetryExecutor executor = new RetryExecutor(properties, Optional.empty());

        assertThat(executor.computeBackoff(1).toMillis()).isEqualTo(100);  // 100 × 2^0
        assertThat(executor.computeBackoff(2).toMillis()).isEqualTo(200);  // 100 × 2^1
        assertThat(executor.computeBackoff(3).toMillis()).isEqualTo(400);  // 100 × 2^2
        assertThat(executor.computeBackoff(4).toMillis()).isEqualTo(500);  // capped
        assertThat(executor.computeBackoff(10).toMillis()).isEqualTo(500); // still capped
    }

    @Test
    void jitter_perturbsBackoff_withinExpectedBounds() {
        // jitter=0.5 means [0.5×base, 1.5×base]. Run many samples and
        // assert all land in range — gives the jitter implementation
        // a probabilistic floor without flakiness.
        for (int i = 0; i < 200; i++) {
            double jittered = RetryExecutor.applyJitter(1000.0, 0.5);
            assertThat(jittered)
                    .as("sample %d should be in [500, 1500]", i)
                    .isBetween(500.0, 1500.0);
        }
    }

    @Test
    void zeroJitter_returnsBaseUnchanged() {
        for (int i = 0; i < 50; i++) {
            assertThat(RetryExecutor.applyJitter(1000.0, 0.0)).isEqualTo(1000.0);
        }
    }
}
