package com.lazydevs.notification.channel.whatsapp.meta;

import com.lazydevs.notification.api.webhook.WebhookHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

import static com.lazydevs.notification.channel.whatsapp.meta.MetaTestSupport.content;
import static com.lazydevs.notification.channel.whatsapp.meta.MetaTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MetaWhatsAppProviderAutoConfiguration}: the prototype provider, the
 * beans it picks up (credentials discovery) and the opt-in webhook.
 */
class MetaWhatsAppProviderAutoConfigurationTest {

    private static final String NAME = "metaWhatsappProvider";

    private final MetaStubServer stub = new MetaStubServer();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MetaWhatsAppProviderAutoConfiguration.class));

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    void registersAPrototypeUnderTheCatalogName_usingPropertiesCredentials_andNoWebhookByDefault() {
        runner.run(ctx -> {
            assertThat(ctx.getBeanFactory().getBeanDefinition(NAME).isPrototype()).isTrue();
            MetaWhatsAppProvider provider = ctx.getBean(NAME, MetaWhatsAppProvider.class);
            assertThat(provider).isNotSameAs(ctx.getBean(NAME, MetaWhatsAppProvider.class));
            provider.configure(stub.properties());
            provider.init();
            assertThat(provider.send(request(), content()).success()).isTrue();
            assertThat(provider.getHealthDetails()).containsEntry("credentials", "properties");
            assertThat(ctx).doesNotHaveBean(WebhookHandler.class);
        });
    }

    @Test
    void credentialsProviderBean_winsOverProperties() {
        runner.withUserConfiguration(CredentialsConfig.class).run(ctx -> {
            MetaWhatsAppProvider provider = ctx.getBean(NAME, MetaWhatsAppProvider.class);
            provider.configure(stub.properties());
            provider.init();

            assertThat(provider.send(request(), content()).success()).isTrue();
            assertThat(CredentialsConfig.CALLS.get()).isEqualTo(1);
            assertThat(stub.requests().getFirst().path()).endsWith("/100000000000042/messages");
        });
    }

    @Test
    void transportBean_isUsed() {
        runner.withUserConfiguration(TransportConfig.class).run(ctx -> {
            MetaWhatsAppProvider provider = ctx.getBean(NAME, MetaWhatsAppProvider.class);
            assertThat(provider.transport()).isSameAs(ctx.getBean(MetaHttpTransport.class));
        });
    }

    @Test
    void webhook_isRegisteredOnlyWhenEnabled_withItsId() {
        runner.withPropertyValues("notification.webhooks.meta.enabled=true",
                        "notification.webhooks.meta.app-secret=your-app-secret",
                        "notification.webhooks.meta.verify-token=your-verify-token")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(WebhookHandler.class).id()).isEqualTo("meta");
                    assertThat(ctx.getBean(MetaWebhookProperties.class).toString())
                            .doesNotContain("your-app-secret", "your-verify-token");
                });
        runner.withPropertyValues("notification.webhooks.meta.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining("app-secret is required"));
    }

    @Test
    void runtimeHints_coverTheProviderAndTheWebhookProperties() {
        RuntimeHints hints = new RuntimeHints();
        new MetaWhatsAppRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection().onType(MetaWhatsAppProvider.class)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(MetaWebhookProperties.class)).accepts(hints);
    }

    @Configuration(proxyBeanMethods = false)
    static class CredentialsConfig {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Bean
        MetaCredentialsProvider metaCredentialsProvider() {
            return tenant -> {
                CALLS.incrementAndGet();
                return new MetaCredentials(MetaStubServer.ACCESS_TOKEN, "100000000000042");
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TransportConfig {
        @Bean
        MetaHttpTransport customTransport() {
            return JdkMetaHttpTransport.shared()::execute;
        }
    }
}
