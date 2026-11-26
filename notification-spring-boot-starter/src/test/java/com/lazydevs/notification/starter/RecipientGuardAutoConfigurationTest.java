package com.lazydevs.notification.starter;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.guard.RecipientGuard;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.core.guard.ConfiguredRecipientGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-34 wiring: {@code notification.recipient-guard.*} binds into the
 * configuration-driven guard, which is validated at startup and can be
 * replaced by an application bean.
 */
class RecipientGuardAutoConfigurationTest {

    private final WebApplicationContextRunner runner = StarterContextRunners.starterRunner();

    @Test
    void off_byDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RecipientGuard.class);
        });
    }

    @Test
    void enabled_bindsTheRules() {
        runner.withPropertyValues(
                        "notification.recipient-guard.enabled=true",
                        "notification.recipient-guard.channels.email.allowed[0]=@example.com",
                        "notification.recipient-guard.channels.email.redirect-to=qa@example.com",
                        "notification.recipient-guard.channels.email.subject-prefix=[qa] ")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RecipientGuard guard = context.getBean(RecipientGuard.class);
                    assertThat(guard).isInstanceOf(ConfiguredRecipientGuard.class);
                    RecipientGuard.Decision decision = guard.check(NotificationRequest.builder()
                            .channel(Channel.EMAIL)
                            .recipient(new EmailRecipient(null, "someone@customer.io", null, null, null, null))
                            .build());
                    assertThat(((EmailRecipient) decision.recipient()).to()).isEqualTo("qa@example.com");
                    assertThat(decision.subjectPrefix()).isEqualTo("[qa]");
                });
    }

    @Test
    void redirectThatIsNotAllowed_failsStartup() {
        runner.withPropertyValues(
                        "notification.recipient-guard.enabled=true",
                        "notification.recipient-guard.channels.sms.allowed[0]=+15550100",
                        "notification.recipient-guard.channels.sms.redirect-to=+15550199")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("channels.sms.redirect-to must itself be allowed");
                });
    }

    @Test
    void anApplicationGuardWins_evenWithTheSwitchOff() {
        runner.withUserConfiguration(CustomGuard.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RecipientGuard.class)).isSameAs(CustomGuard.GUARD);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomGuard {
        static final RecipientGuard GUARD = RecipientGuard.allowAll();

        @Bean
        RecipientGuard customGuard() {
            return GUARD;
        }
    }
}
