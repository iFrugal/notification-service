package com.lazydevs.notification.channel.push.fcm;

import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.TOKEN;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FcmPushProviderAutoConfiguration}: the prototype bean the provider catalog
 * refers to as {@code fcmPushProvider}, and the beans it picks up.
 */
class FcmPushProviderAutoConfigurationTest {

    private static final String NAME = "fcmPushProvider";

    private final FcmStubServer stub = new FcmStubServer();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FcmPushProviderAutoConfiguration.class));

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    void registersPrototypeBeanUnderCatalogName_withTheJdkTransportByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean(NAME);
            assertThat(ctx.getBeanFactory().getBeanDefinition(NAME).isPrototype()).isTrue();
            FcmPushProvider first = ctx.getBean(NAME, FcmPushProvider.class);
            assertThat(first).isNotSameAs(ctx.getBean(NAME, FcmPushProvider.class));

            first.configure(stub.properties());
            first.init();
            assertThat(first.transport()).isSameAs(JdkFcmHttpTransport.shared());
            assertThat(first.send(request(FcmTestSupport.token(TOKEN)), null).success()).isTrue();
        });
    }

    @Test
    void userBeanNamedFcmPushProvider_wins() {
        runner.withUserConfiguration(UserProviderConfig.class).run(ctx -> {
            assertThat(ctx.getBean(NAME)).isSameAs(ctx.getBean(UserProviderConfig.class).provider);
            assertThat(ctx.getBeanFactory().getBeanDefinition(NAME).isPrototype()).isFalse();
        });
    }

    @Test
    void aCustomTransportBean_carriesBothTheTokenAndTheSendCalls() {
        runner.withUserConfiguration(CountingTransportConfig.class).run(ctx -> {
            FcmPushProvider provider = ctx.getBean(NAME, FcmPushProvider.class);
            provider.configure(stub.properties());
            provider.init();

            assertThat(provider.send(request(FcmTestSupport.token(TOKEN)), null).success()).isTrue();

            assertThat(provider.transport()).isSameAs(ctx.getBean(FcmHttpTransport.class));
            assertThat(CountingTransportConfig.CALLS.get()).isEqualTo(2);
        });
    }

    @Test
    void severalTransportBeans_defaultToTheJdkOne_andHttpTransportPicksOneByName() {
        runner.withUserConfiguration(TwoTransportsConfig.class).run(ctx -> {
            FcmPushProvider byDefault = ctx.getBean(NAME, FcmPushProvider.class);
            byDefault.configure(stub.properties());
            assertThat(byDefault.transport()).isSameAs(JdkFcmHttpTransport.shared());

            FcmPushProvider named = ctx.getBean(NAME, FcmPushProvider.class);
            Map<String, Object> props = new java.util.LinkedHashMap<>(stub.properties());
            props.put("http-transport", "tenantBTransport");
            named.configure(props);
            assertThat(named.transport()).isSameAs(ctx.getBean("tenantBTransport"));
        });
    }

    @Test
    void factoryBeans_areAskedFirst() {
        runner.withUserConfiguration(FactoryConfig.class).run(ctx -> {
            FcmPushProvider provider = ctx.getBean(NAME, FcmPushProvider.class);
            provider.configure(Map.of("credentials", "vault:acme", "project-id", "p"));

            assertThat(provider.factories().getFirst()).isSameAs(ctx.getBean(FcmAccessTokenProviderFactory.class));
            assertThat(provider.tokenProvider()).isInstanceOf(FcmPushProviderTest.RecordingTokenProvider.class);
        });
    }

    @Test
    void adcWithoutTheAdapterModule_failsNamingTheArtifact() {
        runner.run(ctx -> assertThatThrownBy(() -> ctx.getBean(NAME, FcmPushProvider.class)
                .configure(Map.of("credentials", "adc", "project-id", "p")))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("com.github.ifrugal:push-provider-fcm-google-auth"));
    }

    @Configuration(proxyBeanMethods = false)
    static class UserProviderConfig {
        final FcmPushProvider provider = new FcmPushProvider();

        @Bean(NAME)
        FcmPushProvider fcmPushProvider() {
            return provider;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CountingTransportConfig {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Bean
        FcmHttpTransport countingTransport() {
            CALLS.set(0);
            return request -> {
                CALLS.incrementAndGet();
                return JdkFcmHttpTransport.shared().execute(request);
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoTransportsConfig {
        @Bean
        FcmHttpTransport tenantATransport() {
            return request -> JdkFcmHttpTransport.shared().execute(request);
        }

        @Bean
        FcmHttpTransport tenantBTransport() {
            return request -> JdkFcmHttpTransport.shared().execute(request);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FactoryConfig {
        @Bean
        FcmAccessTokenProviderFactory vaultFactory() {
            return new FcmPushProviderTest.RecordingFactory();
        }
    }
}
