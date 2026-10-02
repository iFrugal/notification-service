package com.lazydevs.notification.starter;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.exception.ProviderNotFoundException;
import com.lazydevs.notification.channel.email.acs.AcsEmailProvider;
import com.lazydevs.notification.channel.email.ses.SesEmailProvider;
import com.lazydevs.notification.channel.email.smtp.SmtpEmailProvider;
import com.lazydevs.notification.channel.push.fcm.FcmPushProvider;
import com.lazydevs.notification.channel.sms.twilio.TwilioSmsProvider;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves each built-in provider module registers its provider through
 * auto-configuration as a prototype under the conventional bean name, that
 * tenants resolve it by provider name alone, and that a missing or
 * unimplemented provider fails startup with an actionable message.
 */
class ProviderAutoConfigurationTest {

    private static final String SMTP = "notification.tenants.acme.channels.email.providers.smtp.";

    private final WebApplicationContextRunner runner = StarterContextRunners.starterRunner();

    @Test
    void smtp_ses_acs_twilio_fcm_registeredAsPrototypesUnderBuiltInNames() {
        Map<String, Class<?>> expected = Map.of(
                "smtpEmailProvider", SmtpEmailProvider.class,
                "sesEmailProvider", SesEmailProvider.class,
                "acsEmailProvider", AcsEmailProvider.class,
                "twilioSmsProvider", TwilioSmsProvider.class,
                "fcmPushProvider", FcmPushProvider.class);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            expected.forEach((beanName, type) -> {
                assertThat(context.getBeanFactory().getBeanDefinition(beanName).isPrototype())
                        .as("%s is a prototype", beanName).isTrue();
                assertThat(context.getBean(beanName)).isInstanceOf(type);
            });
            assertThat(context.getBean("fcmPushProvider")).isNotSameAs(context.getBean("fcmPushProvider"));
        });
    }

    @Test
    void builtInName_givesEachTenantAFreshInstance() {
        runner.withPropertyValues(
                        SMTP + "properties.host=smtp.acme.example",
                        "notification.tenants.globex.channels.email.providers.smtp.properties.host=smtp.globex.example")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("smtpEmailProvider"))
                            .isNotSameAs(context.getBean("smtpEmailProvider"));

                    ProviderRegistry registry = context.getBean(ProviderRegistry.class);
                    NotificationProvider acme = registry.getProvider("acme", Channel.EMAIL, null);
                    NotificationProvider globex = registry.getProvider("globex", Channel.EMAIL, null);
                    assertThat(acme).isInstanceOf(SmtpEmailProvider.class);
                    assertThat(globex).isInstanceOf(SmtpEmailProvider.class);
                    assertThat(acme).isNotSameAs(globex);
                });
    }

    @Test
    void missingModule_failsNamingMavenArtifact() {
        runner.withClassLoader(new FilteredClassLoader(SesV2Client.class))
                .withPropertyValues("notification.tenants.acme.channels.email.providers.ses.properties.region=us-east-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause()
                            .isInstanceOf(ProviderNotFoundException.class)
                            .hasMessage("Built-in provider 'ses' for channel 'email' is not on the classpath. "
                                    + "Add the Maven dependency com.github.ifrugal:email-provider-ses "
                                    + "(same version as notification-core), or set 'beanName' or 'fqcn' under "
                                    + "notification.tenants.acme.channels.email.providers.ses.");
                });
    }

    @Test
    void unimplementedBuiltIn_failsSayingNotImplemented() {
        runner.withPropertyValues("notification.tenants.acme.channels.sms.providers.sns.properties.region=us-east-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause()
                            .isInstanceOf(ProviderNotFoundException.class)
                            .hasMessage("Built-in provider 'sns' for channel 'sms' is not implemented in this release. "
                                    + "Set 'beanName' or 'fqcn' to a custom implementation.");
                });
    }

    @Test
    void unknownProvider_failsListingAvailableBuiltIns() {
        runner.withPropertyValues("notification.tenants.acme.channels.email.providers.mailgun.properties.key=k")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause()
                            .isInstanceOf(ProviderNotFoundException.class)
                            .hasMessageContaining("Provider 'mailgun' not found for channel 'EMAIL'")
                            .hasMessageContaining("Available built-in providers for EMAIL: [smtp, ses, acs]");
                });
    }

    @Test
    void userBeanWithConventionalName_overridesBuiltIn() {
        runner.withUserConfiguration(ConventionalNameProviderConfiguration.class)
                .withPropertyValues(SMTP + "properties.host=smtp.acme.example")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("smtpEmailProvider")).isInstanceOf(StubEmailProvider.class);
                    assertThat(context).doesNotHaveBean(SmtpEmailProvider.class);
                    NotificationProvider provider = context.getBean(ProviderRegistry.class)
                            .getProvider("acme", Channel.EMAIL, "smtp");
                    assertThat(provider).isInstanceOf(StubEmailProvider.class);
                    assertThat(((StubEmailProvider) provider).configuration())
                            .containsEntry("host", "smtp.acme.example");
                });
    }

    @Test
    void configBeanName_overridesBuiltIn() {
        runner.withUserConfiguration(CustomNameProviderConfiguration.class)
                .withPropertyValues(SMTP + "bean-name=customEmailProvider")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    NotificationProvider provider = context.getBean(ProviderRegistry.class)
                            .getProvider("acme", Channel.EMAIL, "smtp");
                    assertThat(provider).isInstanceOf(StubEmailProvider.class);
                });
    }

    @Test
    void configFqcn_overridesBuiltIn() {
        runner.withPropertyValues(SMTP + "fqcn=" + StubEmailProvider.class.getName())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    NotificationProvider provider = context.getBean(ProviderRegistry.class)
                            .getProvider("acme", Channel.EMAIL, "smtp");
                    assertThat(provider).isInstanceOf(StubEmailProvider.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ConventionalNameProviderConfiguration {
        @Bean("smtpEmailProvider")
        @Scope("prototype")
        StubEmailProvider smtpEmailProvider() {
            return new StubEmailProvider();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomNameProviderConfiguration {
        @Bean
        StubEmailProvider customEmailProvider() {
            return new StubEmailProvider();
        }
    }
}
