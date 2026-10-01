package com.lazydevs.notification.core.config;

import com.lazydevs.notification.core.config.NotificationProperties.DeadLetterProperties;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean-validation tests for {@link DeadLetterProperties}, in particular the
 * replay lease that keeps concurrent replays from taking the same entry.
 */
class DeadLetterPropertiesValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.byDefaultProvider()
                .configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void defaults_passValidation_withFiveMinuteReplayLease() {
        DeadLetterProperties p = new DeadLetterProperties();
        assertThat(p.getReplayLease()).isEqualTo(Duration.ofMinutes(5));
        assertThat(validator.validate(p)).isEmpty();
    }

    @Test
    void replayLease_zeroNegativeOrNull_isRejected() {
        for (Duration lease : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), null}) {
            DeadLetterProperties p = new DeadLetterProperties();
            p.setReplayLease(lease);
            assertThat(validator.validate(p))
                    .as("lease %s", lease)
                    .extracting(ConstraintViolation::getMessage)
                    .contains("dead-letter replay-lease must be positive");
        }
    }
}
