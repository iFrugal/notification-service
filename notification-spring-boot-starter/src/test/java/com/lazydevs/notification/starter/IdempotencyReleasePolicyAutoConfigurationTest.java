package com.lazydevs.notification.starter;

import com.lazydevs.notification.api.idempotency.IdempotencyReleasePolicy;
import com.lazydevs.notification.core.idempotency.FailurePolicyReleasePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-32 wiring: the failure-policy release decision is a bean an application
 * can replace.
 */
class IdempotencyReleasePolicyAutoConfigurationTest {

    private final WebApplicationContextRunner runner = StarterContextRunners.starterRunner();

    @Test
    void theDefaultFollowsTheFailurePolicy() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IdempotencyReleasePolicy.class)).isInstanceOf(FailurePolicyReleasePolicy.class);
        });
    }

    @Test
    void anApplicationPolicyWins() {
        runner.withUserConfiguration(CustomPolicy.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IdempotencyReleasePolicy.class)).isSameAs(CustomPolicy.POLICY);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomPolicy {
        static final IdempotencyReleasePolicy POLICY = (request, failed) -> false;

        @Bean
        IdempotencyReleasePolicy customReleasePolicy() {
            return POLICY;
        }
    }
}
