package com.lazydevs.notification.starter;

import com.lazydevs.notification.core.health.DeadLetterStoreHealthIndicator;
import com.lazydevs.notification.core.health.DeliveryEventStoreHealthIndicator;
import com.lazydevs.notification.core.health.IdempotencyStoreHealthIndicator;
import com.lazydevs.notification.core.health.RateLimiterHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves each DD-21 health indicator registers exactly when its store or
 * limiter is present, under the indicator name used by {@code /actuator/health}.
 */
class HealthAutoConfigurationTest {

    private final WebApplicationContextRunner runner = StarterContextRunners.starterRunner();

    @Test
    void deadLetterEnabled_registersDlqIndicator() {
        runner.withPropertyValues("notification.dead-letter.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("dlq");
            assertThat(context.getBean("dlq")).isInstanceOf(DeadLetterStoreHealthIndicator.class);
        });
    }

    @Test
    void deliveryEventsEnabled_registersDeliveryEventsIndicator() {
        runner.withPropertyValues("notification.delivery-events.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("deliveryEvents")).isInstanceOf(DeliveryEventStoreHealthIndicator.class);
        });
    }

    @Test
    void byDefault_registersIdempotencyIndicatorOnly() {
        // Idempotency is on by default, so its indicator is too; the
        // opt-in features stay off and so do their indicators.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("idempotency");
            assertThat(context.getBean("idempotency")).isInstanceOf(IdempotencyStoreHealthIndicator.class);
            assertThat(context).doesNotHaveBean("dlq");
            assertThat(context).doesNotHaveBean("deliveryEvents");
            assertThat(context).doesNotHaveBean("rateLimit");
        });
    }

    @Test
    void idempotencyDisabled_registersNoIdempotencyIndicator() {
        runner.withPropertyValues("notification.idempotency.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("idempotency");
        });
    }

    @Test
    void rateLimitEnabled_registersRateLimitIndicator() {
        runner.withPropertyValues("notification.rate-limit.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("rateLimit")).isInstanceOf(RateLimiterHealthIndicator.class);
        });
    }

    /** Every SPI on, so each indicator would register unless switched off. */
    private static final String[] ALL_FEATURES = {
            "notification.dead-letter.enabled=true",
            "notification.delivery-events.enabled=true",
            "notification.rate-limit.enabled=true",
    };

    @Test
    void allFeaturesOn_registersAllFourIndicators() {
        runner.withPropertyValues(ALL_FEATURES).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("dlq").hasBean("deliveryEvents").hasBean("idempotency").hasBean("rateLimit");
        });
    }

    @Test
    void managementHealthNameEnabledFalse_removesOnlyThatIndicator() {
        runner.withPropertyValues(ALL_FEATURES)
                .withPropertyValues("management.health.delivery-events.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("deliveryEvents");
                    assertThat(context).hasBean("dlq").hasBean("idempotency").hasBean("rateLimit");
                });
    }

    @Test
    void managementHealthNameEnabledFalse_acceptsTheCamelCaseName() {
        runner.withPropertyValues(ALL_FEATURES)
                .withPropertyValues("management.health.deliveryEvents.enabled=false",
                        "management.health.rateLimit.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("deliveryEvents").doesNotHaveBean("rateLimit");
                    assertThat(context).hasBean("dlq").hasBean("idempotency");
                });
    }

    @Test
    void eachIndicatorHasItsOwnSwitch() {
        runner.withPropertyValues(ALL_FEATURES)
                .withPropertyValues("management.health.dlq.enabled=false",
                        "management.health.idempotency.enabled=false",
                        "management.health.rate-limit.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("dlq").doesNotHaveBean("idempotency")
                            .doesNotHaveBean("rateLimit");
                    assertThat(context).hasBean("deliveryEvents");
                    // The stores themselves stay; only their indicators go.
                    assertThat(context).hasSingleBean(com.lazydevs.notification.api.deadletter.DeadLetterStore.class);
                });
    }

    @Test
    void managementHealthDefaultsEnabledFalse_removesAllFour_unlessOneIsEnabledExplicitly() {
        runner.withPropertyValues(ALL_FEATURES)
                .withPropertyValues("management.health.defaults.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("dlq").doesNotHaveBean("deliveryEvents")
                            .doesNotHaveBean("idempotency").doesNotHaveBean("rateLimit");
                });
        runner.withPropertyValues(ALL_FEATURES)
                .withPropertyValues("management.health.defaults.enabled=false",
                        "management.health.dlq.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("dlq");
                    assertThat(context).doesNotHaveBean("idempotency");
                });
    }

    @Test
    void rateLimitDisabled_registersNoRateLimitIndicator() {
        runner.withPropertyValues("notification.rate-limit.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("rateLimit");
        });
    }
}
