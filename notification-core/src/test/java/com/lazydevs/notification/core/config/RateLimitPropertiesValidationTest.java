package com.lazydevs.notification.core.config;

import com.lazydevs.notification.core.config.NotificationProperties.RateLimitOverride;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitRule;
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
 * Bean-validation tests for the DD-12 rate-limit config types. Ensures
 * invalid YAML is rejected at binding time rather than failing later
 * inside Bucket4j with a less helpful error.
 */
class RateLimitPropertiesValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        // ParameterMessageInterpolator avoids the jakarta.el dependency
        // that Hibernate Validator's default ResourceBundleMessageInterpolator
        // expects. EL is provided in production by Spring Boot (servlet
        // container brings it transitively); here we want this test to
        // run on the slimmer notification-core test classpath.
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
    void defaultRule_passesValidation() {
        RateLimitRule rule = new RateLimitRule(200, 100, Duration.ofSeconds(1));
        assertThat(validator.validate(rule)).isEmpty();
    }

    @Test
    void zeroCapacity_isRejected() {
        RateLimitRule rule = new RateLimitRule(0, 0, Duration.ofSeconds(1));
        Set<ConstraintViolation<RateLimitRule>> violations = validator.validate(rule);
        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .anySatisfy(m -> assertThat(m).contains("capacity"));
    }

    @Test
    void zeroRefillTokens_isRejected() {
        RateLimitRule rule = new RateLimitRule(100, 0, Duration.ofSeconds(1));
        Set<ConstraintViolation<RateLimitRule>> violations = validator.validate(rule);
        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .anySatisfy(m -> assertThat(m).contains("refillTokens"));
    }

    @Test
    void refillTokensExceedingCapacity_isRejected() {
        RateLimitRule rule = new RateLimitRule(10, 100, Duration.ofSeconds(1));
        Set<ConstraintViolation<RateLimitRule>> violations = validator.validate(rule);
        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .anySatisfy(m -> assertThat(m).contains("refillTokens must not exceed capacity"));
    }

    @Test
    void zeroRefillPeriod_isRejected() {
        RateLimitRule rule = new RateLimitRule(100, 50, Duration.ZERO);
        Set<ConstraintViolation<RateLimitRule>> violations = validator.validate(rule);
        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .anySatisfy(m -> assertThat(m).contains("refillPeriod must be positive"));
    }

    @Test
    void negativeRefillPeriod_isRejected() {
        RateLimitRule rule = new RateLimitRule(100, 50, Duration.ofSeconds(-1));
        Set<ConstraintViolation<RateLimitRule>> violations = validator.validate(rule);
        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .anySatisfy(m -> assertThat(m).contains("refillPeriod must be positive"));
    }

    @Test
    void overrideWithBlankTenant_isRejected() {
        RateLimitOverride o = new RateLimitOverride();
        o.setTenant("");
        o.setCapacity(50);
        o.setRefillTokens(50);
        o.setRefillPeriod(Duration.ofSeconds(1));

        Set<ConstraintViolation<RateLimitOverride>> violations = validator.validate(o);
        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .anySatisfy(m -> assertThat(m).contains("tenant is required"));
    }

    @Test
    void validOverride_passesValidation() {
        RateLimitOverride o = new RateLimitOverride();
        o.setTenant("acme");
        o.setCaller("billing");
        o.setChannel("email");
        o.setCapacity(50);
        o.setRefillTokens(20);
        o.setRefillPeriod(Duration.ofSeconds(1));

        assertThat(validator.validate(o)).isEmpty();
    }
}
