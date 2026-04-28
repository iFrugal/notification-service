package com.lazydevs.notification.core.config;

import com.lazydevs.notification.core.config.NotificationProperties.RetryProperties;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean-validation tests for the DD-13 {@link RetryProperties} after the
 * round-2 review tightened the constraints (max-attempts cap and
 * positive-only initialDelay).
 */
class RetryPropertiesValidationTest {

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
    void defaults_passValidation() {
        RetryProperties p = new RetryProperties();
        assertThat(validator.validate(p)).isEmpty();
    }

    @Test
    void maxAttempts_zero_isRejected() {
        RetryProperties p = new RetryProperties();
        p.setMaxAttempts(0);
        Set<ConstraintViolation<RetryProperties>> v = validator.validate(p);
        assertThat(v).extracting(ConstraintViolation::getMessage)
                .anySatisfy(m -> assertThat(m).contains("max-attempts must be at least 1"));
    }

    @Test
    void maxAttempts_above10_isRejected() {
        // CR-4: doc says capped at 10, validation now enforces it.
        RetryProperties p = new RetryProperties();
        p.setMaxAttempts(11);
        Set<ConstraintViolation<RetryProperties>> v = validator.validate(p);
        assertThat(v).extracting(ConstraintViolation::getMessage)
                .anySatisfy(m -> assertThat(m).contains("max-attempts must be at most 10"));
    }

    @Test
    void initialDelay_zero_isRejected() {
        // CR-5: zero defeats backoff per class doc — now rejected.
        RetryProperties p = new RetryProperties();
        p.setInitialDelay(Duration.ZERO);
        Set<ConstraintViolation<RetryProperties>> v = validator.validate(p);
        assertThat(v).extracting(ConstraintViolation::getMessage)
                .anySatisfy(m -> assertThat(m).contains("initialDelay must be positive"));
    }

    @Test
    void initialDelay_negative_isRejected() {
        RetryProperties p = new RetryProperties();
        p.setInitialDelay(Duration.ofSeconds(-1));
        Set<ConstraintViolation<RetryProperties>> v = validator.validate(p);
        assertThat(v).extracting(ConstraintViolation::getMessage)
                .anySatisfy(m -> assertThat(m).contains("initialDelay must be positive"));
    }

    @Test
    void maxDelay_belowInitial_isRejected() {
        // Pre-existing constraint: maxDelay must be >= initialDelay.
        RetryProperties p = new RetryProperties();
        p.setInitialDelay(Duration.ofSeconds(10));
        p.setMaxDelay(Duration.ofSeconds(5));
        Set<ConstraintViolation<RetryProperties>> v = validator.validate(p);
        assertThat(v).extracting(ConstraintViolation::getMessage)
                .anySatisfy(m -> assertThat(m).contains("maxDelay"));
    }

    @Test
    void multiplier_belowOne_isRejected() {
        RetryProperties p = new RetryProperties();
        p.setMultiplier(0.5);
        Set<ConstraintViolation<RetryProperties>> v = validator.validate(p);
        assertThat(v).extracting(ConstraintViolation::getMessage)
                .anySatisfy(m -> assertThat(m).contains("multiplier"));
    }

    @Test
    void jitter_outOfRange_isRejected() {
        RetryProperties p = new RetryProperties();
        p.setJitter(1.5);
        Set<ConstraintViolation<RetryProperties>> v = validator.validate(p);
        assertThat(v).extracting(ConstraintViolation::getMessage)
                .anySatisfy(m -> assertThat(m).contains("jitter"));
    }
}
