package com.lazydevs.notification.core.config;

import com.lazydevs.notification.core.config.NotificationProperties.IdempotencyProperties;
import com.lazydevs.notification.core.idempotency.IdempotencyFailurePolicy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-32 idempotency settings: binding, the deprecated alias and validation.
 */
@SuppressWarnings("deprecation") // exercises the deprecated retry-after-failure alias on purpose
class IdempotencyPropertiesTest {

    @Test
    void defaults_preserveThe120Behaviour() {
        IdempotencyProperties p = new IdempotencyProperties();

        assertThat(p.resolvedFailurePolicy()).isEqualTo(IdempotencyFailurePolicy.RELEASE_ALL);
        assertThat(p.isRetryAfterFailure()).isTrue();
        assertThat(p.isRejectKeyReuse()).isFalse();
        assertThat(p.getInProgressTimeout()).isNull();
        assertThat(p.hasConflictingFailurePolicy()).isFalse();
    }

    @Test
    void bindsTheNewPropertiesWithRelaxedNames() {
        IdempotencyProperties p = bind(Map.of(
                "notification.idempotency.failure-policy", "release-transient",
                "notification.idempotency.reject-key-reuse", "true",
                "notification.idempotency.in-progress-timeout", "PT10M"));

        assertThat(p.resolvedFailurePolicy()).isEqualTo(IdempotencyFailurePolicy.RELEASE_TRANSIENT);
        assertThat(p.isRejectKeyReuse()).isTrue();
        assertThat(p.getInProgressTimeout()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void retryAfterFailureFalse_isAnAliasForKeep() {
        IdempotencyProperties p = bind(Map.of("notification.idempotency.retry-after-failure", "false"));

        assertThat(p.getRetryAfterFailure()).isFalse();
        assertThat(p.isRetryAfterFailure()).isFalse();
        assertThat(p.resolvedFailurePolicy()).isEqualTo(IdempotencyFailurePolicy.KEEP);
    }

    @Test
    void explicitFailurePolicyWins_andAConflictIsReported() {
        IdempotencyProperties p = bind(Map.of(
                "notification.idempotency.retry-after-failure", "false",
                "notification.idempotency.failure-policy", "RELEASE_ALL"));

        assertThat(p.resolvedFailurePolicy()).isEqualTo(IdempotencyFailurePolicy.RELEASE_ALL);
        assertThat(p.hasConflictingFailurePolicy()).isTrue();

        p.setRetryAfterFailure(true);
        assertThat(p.hasConflictingFailurePolicy()).isFalse();
        p.setFailurePolicy(IdempotencyFailurePolicy.RELEASE_TRANSIENT);
        assertThat(p.hasConflictingFailurePolicy()).isTrue();
    }

    @Test
    void inProgressTimeout_mustBePositiveWhenSet() {
        try (ValidatorFactory factory = Validation.byDefaultProvider().configure()
                .messageInterpolator(new ParameterMessageInterpolator()).buildValidatorFactory()) {
            for (Duration bad : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
                IdempotencyProperties p = new IdempotencyProperties();
                p.setInProgressTimeout(bad);
                assertThat(factory.getValidator().validate(p))
                        .extracting(ConstraintViolation::getMessage)
                        .contains("idempotency in-progress-timeout must be positive");
            }
            IdempotencyProperties ok = new IdempotencyProperties();
            ok.setInProgressTimeout(Duration.ofSeconds(30));
            assertThat(factory.getValidator().validate(ok)).isEmpty();
        }
    }

    @Test
    void policySemantics() {
        assertThat(IdempotencyFailurePolicy.RELEASE_ALL.releases(null)).isTrue();
        assertThat(IdempotencyFailurePolicy.KEEP.releases(com.lazydevs.notification.api.model.FailureType.TRANSIENT))
                .isFalse();
        assertThat(IdempotencyFailurePolicy.RELEASE_TRANSIENT.releases(
                com.lazydevs.notification.api.model.FailureType.TRANSIENT)).isTrue();
        assertThat(IdempotencyFailurePolicy.RELEASE_TRANSIENT.releases(null)).isFalse();
        assertThat(IdempotencyFailurePolicy.RELEASE_TRANSIENT.replaysKeptFailures()).isTrue();
        assertThat(IdempotencyFailurePolicy.KEEP.replaysKeptFailures()).isFalse();
    }

    private static IdempotencyProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("notification", NotificationProperties.class)
                .get()
                .getIdempotency();
    }
}
