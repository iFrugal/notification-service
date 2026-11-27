package com.lazydevs.notification.starter;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.template.TemplateChangeFeed;
import com.lazydevs.notification.api.template.TemplateChangeListener;
import com.lazydevs.notification.api.template.TemplateDocument;
import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.api.template.TemplateSource;
import com.lazydevs.notification.api.template.TemplateStore;
import com.lazydevs.notification.api.template.TemplateVersion;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import com.lazydevs.notification.core.template.ResourceLoaderTemplateSource;
import com.lazydevs.notification.core.template.TemplateChangeDispatcher;
import com.lazydevs.notification.core.template.admin.TemplateAdminService;
import com.lazydevs.notification.core.template.store.InMemoryTemplateStore;
import com.lazydevs.notification.rest.template.ActorResolver;
import com.lazydevs.notification.rest.template.TemplateAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-38 in starter mode: the template store is opt-in, its absence changes nothing, and
 * enabling it wires the store, the change propagation, the admin service and (behind its own
 * switch) the REST API.
 */
class TemplateStoreStarterTest {

    private static final String MEMORY = "notification.template.store.type=memory";
    private static final String NO_LEGACY = "notification.template.legacy-helpers=false";

    private final WebApplicationContextRunner runner = StarterContextRunners.starterRunner();

    private static RenderedContent render(NotificationTemplateEngine engine) {
        return engine.render(NotificationRequest.builder().tenantId("acme").channel(Channel.EMAIL)
                .templateId("DELIVERY_EVENT_TEST").build());
    }

    @Test
    void storeOff_noStoreBeansAndTheEngineRendersAsBefore() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TemplateStore.class);
            assertThat(context).doesNotHaveBean(TemplateChangeFeed.class);
            assertThat(context).doesNotHaveBean(TemplateChangeDispatcher.class);
            assertThat(context).doesNotHaveBean(TemplateAdminService.class);
            assertThat(context).doesNotHaveBean(TemplateAdminController.class);
            assertThat(context).getBean(TemplateSource.class).isInstanceOf(ResourceLoaderTemplateSource.class);
            NotificationTemplateEngine engine = context.getBean(NotificationTemplateEngine.class);
            assertThat(engine.isStoreConfigured()).isFalse();

            RenderedContent before = render(new NotificationTemplateEngine(
                    context.getBean(NotificationProperties.class), new DefaultResourceLoader()));
            assertThat(render(engine)).isEqualTo(before);
            assertThat(before.subject()).isEqualTo("Delivery event test");
            assertThat(before.textBody()).isEqualTo("Hello");
        });
    }

    @Test
    void storeWithLegacyHelpersFailsStartup() {
        runner.withPropertyValues(MEMORY).run(context -> assertThat(context).hasFailed().getFailure()
                .hasStackTraceContaining("notification.template.legacy-helpers=false"));
    }

    @Test
    void jdbcTypeWithoutTheModuleFailsStartup() {
        runner.withPropertyValues("notification.template.store.type=jdbc", NO_LEGACY)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("add notification-store-jdbc"));
    }

    @Test
    void memoryStoreWiresTheAdminServiceAndTellsEveryListener() {
        runner.withPropertyValues(MEMORY, NO_LEGACY)
                .withUserConfiguration(RecordingListener.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(TemplateStore.class).isInstanceOf(InMemoryTemplateStore.class);
                    assertThat(context).hasSingleBean(TemplateChangeDispatcher.class);
                    assertThat(context).doesNotHaveBean(TemplateChangeFeed.class);
                    NotificationTemplateEngine engine = context.getBean(NotificationTemplateEngine.class);
                    assertThat(engine.isStoreConfigured()).isTrue();
                    assertThat(render(engine).subject()).isEqualTo("Delivery event test");

                    TemplateAdminService admin = context.getBean(TemplateAdminService.class);
                    TemplateKey key = TemplateKey.of(null, "email", "DELIVERY_EVENT_TEST");
                    TemplateVersion draft = admin.saveDraft(key, "[SUBJECT]Edited[/SUBJECT]Body", Map.of(), 0,
                            "ann", null);
                    admin.publish(key, draft.revision(), "ann", "edit");

                    assertThat(render(engine).subject()).isEqualTo("Edited");
                    assertThat(context.getBean(RecordingListener.class).seen).containsExactly(key);
                    assertThat(context).doesNotHaveBean(TemplateAdminController.class);
                });
    }

    @Test
    void restSwitchNeedsAStore() {
        runner.withPropertyValues("notification.rest.enabled=true", "notification.rest.admin.templates.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("needs a template store"));
    }

    @Test
    void restSwitchExposesTheController() {
        runner.withPropertyValues("notification.rest.enabled=true", "notification.rest.admin.templates.enabled=true",
                        MEMORY, NO_LEGACY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TemplateAdminController.class);
                    assertThat(context).hasSingleBean(ActorResolver.class);
                });
        runner.withPropertyValues("notification.rest.enabled=true", MEMORY, NO_LEGACY)
                .run(context -> assertThat(context).doesNotHaveBean(TemplateAdminController.class));
    }

    @Test
    void customTemplateSourceReplacesTheDefault() {
        runner.withUserConfiguration(CustomSource.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ResourceLoaderTemplateSource.class);
            assertThat(render(context.getBean(NotificationTemplateEngine.class)).subject()).isEqualTo("custom");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingListener implements TemplateChangeListener {
        final List<TemplateKey> seen = new ArrayList<>();

        @Override
        public void templateChanged(TemplateKey key) {
            seen.add(key);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomSource {
        @Bean
        TemplateSource customTemplateSource() {
            return new TemplateSource() {
                @Override
                public Optional<TemplateDocument> find(TemplateKey key) {
                    return Optional.of(TemplateDocument.builtIn(key.name(), "[SUBJECT]custom[/SUBJECT]x", -1));
                }

                @Override
                public Optional<TemplateDocument> findInclude(String name, Charset charset) {
                    return Optional.empty();
                }
            };
        }
    }
}
