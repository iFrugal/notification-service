package com.lazydevs.notification.channel.whatsapp;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.core.template.ChannelContentRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppChannelAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WhatsAppChannelAutoConfiguration.class));

    @Test
    void registersRegistryAndRenderer_bindingTheYaml() {
        runner.withPropertyValues(
                        "notification.whatsapp.templates.default.order-shipped.name=order_shipped",
                        "notification.whatsapp.templates.default.order-shipped.language=en",
                        "notification.whatsapp.templates.default.order-shipped.body[0]=customerName",
                        "notification.whatsapp.templates.default.order-shipped.buttons[0].index=0",
                        "notification.whatsapp.templates.default.order-shipped.buttons[0].sub-type=url",
                        "notification.whatsapp.templates.default.order-shipped.buttons[0].variable=token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WhatsAppTemplateRegistry.class);
                    assertThat(context.getBean(ChannelContentRenderer.class).channel()).isEqualTo(Channel.WHATSAPP);
                    WhatsAppTemplateBinding binding = context.getBean(WhatsAppTemplateRegistry.class)
                            .find("acme", "order-shipped").orElseThrow();
                    assertThat(binding.body()).containsExactly("customerName");
                    assertThat(binding.buttons()).containsExactly(
                            new WhatsAppTemplateBinding.Button(0, "url", "token"));
                });
    }

    @Test
    void invalidYaml_failsStartup() {
        runner.withPropertyValues("notification.whatsapp.templates.default.broken.language=en")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("notification.whatsapp.templates.default.broken: name is required"));
    }

    @Test
    void userRegistryReplacesTheYamlOne() {
        runner.withUserConfiguration(UserRegistry.class).run(context -> {
            assertThat(context).hasSingleBean(WhatsAppTemplateRegistry.class);
            assertThat(context).doesNotHaveBean(PropertiesWhatsAppTemplateRegistry.class);
            assertThat(context).hasSingleBean(WhatsAppContentRenderer.class);
        });
    }

    @Test
    void runtimeHints_coverThePropertiesBinding() {
        RuntimeHints hints = new RuntimeHints();
        new WhatsAppRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection().onType(WhatsAppProperties.class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(WhatsAppProperties.Template.class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(WhatsAppProperties.Button.class)).accepts(hints);
    }

    @Configuration(proxyBeanMethods = false)
    static class UserRegistry {
        @Bean
        WhatsAppTemplateRegistry databaseRegistry() {
            return (tenant, id) -> Optional.empty();
        }
    }
}
