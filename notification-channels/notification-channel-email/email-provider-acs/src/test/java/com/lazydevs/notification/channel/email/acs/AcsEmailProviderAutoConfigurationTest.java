package com.lazydevs.notification.channel.email.acs;

import com.azure.communication.email.EmailClient;
import com.azure.core.credential.TokenCredential;
import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@link AcsEmailProviderAutoConfiguration}: the prototype bean the provider
 * catalog refers to as {@code acsEmailProvider}.
 */
class AcsEmailProviderAutoConfigurationTest {

    private static final String NAME = "acsEmailProvider";
    private static final String CONNECTION_STRING =
            "endpoint=https://unit.communication.azure.com/;accesskey=c2VjcmV0LWtleQ==";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AcsEmailProviderAutoConfiguration.class));

    @Test
    void registersPrototypeBeanUnderCatalogName() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean(NAME);
            assertThat(ctx.getBeanFactory().getBeanDefinition(NAME).isPrototype()).isTrue();
            AcsEmailProvider first = ctx.getBean(NAME, AcsEmailProvider.class);
            AcsEmailProvider second = ctx.getBean(NAME, AcsEmailProvider.class);
            assertThat(first).isNotSameAs(second);
        });
    }

    @Test
    void backsOffWithoutTheAcsSdk() {
        runner.withClassLoader(new FilteredClassLoader(EmailClient.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(NAME));
    }

    @Test
    void userBeanNamedAcsEmailProvider_wins() {
        runner.withUserConfiguration(UserProviderConfig.class).run(ctx -> {
            assertThat(ctx.getBean(NAME)).isSameAs(ctx.getBean(UserProviderConfig.class).provider);
            assertThat(ctx.getBeanFactory().getBeanDefinition(NAME).isPrototype()).isFalse();
        });
    }

    @Test
    void configureAndInit_withConnectionString_buildsClient() {
        runner.run(ctx -> {
            AcsEmailProvider provider = ctx.getBean(NAME, AcsEmailProvider.class);
            provider.configure(Map.of("connection-string", CONNECTION_STRING, "sender", "DoNotReply@example.com"));
            provider.init();

            assertThat(provider.gateway()).isInstanceOf(SdkAcsEmailGateway.class);
            assertThat(provider.isHealthy()).isTrue();
        });
    }

    @Test
    void defaultCredential_withoutAzureIdentity_failsInConfigure() {
        runner.withClassLoader(new FilteredClassLoader("com.azure.identity")).run(ctx -> {
            AcsEmailProvider provider = ctx.getBean(NAME, AcsEmailProvider.class);

            assertThatThrownBy(() -> provider.configure(Map.of(
                    "endpoint", "https://unit.communication.azure.com",
                    "credential", "default",
                    "sender", "DoNotReply@example.com")))
                    .isInstanceOf(ProviderConfigurationException.class)
                    .hasMessageContaining("needs com.azure:azure-identity on the classpath");
        });
    }

    @Test
    void namedCredential_resolvesTokenCredentialBean() {
        runner.withUserConfiguration(CredentialsConfig.class).run(ctx -> {
            AcsEmailProvider provider = ctx.getBean(NAME, AcsEmailProvider.class);
            provider.configure(Map.of(
                    "endpoint", "https://unit.communication.azure.com",
                    "credential", "tenantBCredential",
                    "sender", "DoNotReply@example.com"));
            provider.init();

            assertThat(provider.credential()).isSameAs(ctx.getBean("tenantBCredential"));
            assertThat(provider.gateway()).isInstanceOf(SdkAcsEmailGateway.class);
        });
    }

    @Test
    void namedCredential_withoutAnyTokenCredentialBean_failsClearly() {
        runner.run(ctx -> {
            AcsEmailProvider provider = ctx.getBean(NAME, AcsEmailProvider.class);

            assertThatThrownBy(() -> provider.configure(Map.of(
                    "endpoint", "https://unit.communication.azure.com",
                    "credential", "missing",
                    "sender", "DoNotReply@example.com")))
                    .isInstanceOf(ProviderConfigurationException.class)
                    .hasMessageContaining("no TokenCredential bean named 'missing'");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserProviderConfig {
        final AcsEmailProvider provider = new AcsEmailProvider();

        @Bean(NAME)
        AcsEmailProvider acsEmailProvider() {
            return provider;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CredentialsConfig {
        @Bean
        TokenCredential tenantACredential() {
            return mock(TokenCredential.class);
        }

        @Bean
        TokenCredential tenantBCredential() {
            return mock(TokenCredential.class);
        }
    }
}
