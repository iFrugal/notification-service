package com.lazydevs.notification.starter;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.push.PushDevice;
import com.lazydevs.notification.api.push.PushDeviceRegistration;
import com.lazydevs.notification.api.push.PushDeviceRegistry;
import com.lazydevs.notification.api.push.PushPlatform;
import com.lazydevs.notification.core.push.InMemoryPushDeviceRegistry;
import com.lazydevs.notification.core.push.PushContentRenderer;
import com.lazydevs.notification.core.push.PushDeviceCleanupListener;
import com.lazydevs.notification.core.template.ChannelContentRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-37 wiring in starter mode: the registry, the cleanup listener, user targeting and the
 * push content renderer.
 */
class PushAutoConfigurationTest {

    private final WebApplicationContextRunner runner = StarterContextRunners.starterRunner();

    @Test
    void byDefault_thereIsNoRegistry_butThePushRendererIsRegistered() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PushDeviceRegistry.class);
            assertThat(context).doesNotHaveBean(PushDeviceCleanupListener.class);
            assertThat(context).hasSingleBean(PushContentRenderer.class);
        });
    }

    @Test
    void devicesEnabled_registersTheInMemoryRegistry_withTheConfiguredLimits_andTheCleanupListener() {
        runner.withPropertyValues("notification.push.devices.enabled=true",
                        "notification.push.devices.max-devices-per-user=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(PushDeviceRegistry.class).isInstanceOf(InMemoryPushDeviceRegistry.class);
                    assertThat(context).hasSingleBean(PushDeviceCleanupListener.class);
                    PushDeviceRegistry registry = context.getBean(PushDeviceRegistry.class);
                    registry.register(PushDeviceRegistration.of("u1", "i1", PushPlatform.ANDROID, "t1"));
                    registry.register(PushDeviceRegistration.of("u1", "i2", PushPlatform.ANDROID, "t2"));
                    assertThat(registry.activeDevices("u1", null)).hasSize(1);
                });
    }

    @Test
    void cleanupCanBeSwitchedOff() {
        runner.withPropertyValues("notification.push.devices.enabled=true",
                        "notification.push.devices.cleanup-on-invalid-target=false")
                .run(context -> assertThat(context).doesNotHaveBean(PushDeviceCleanupListener.class));
    }

    @Test
    void anApplicationRegistry_winsAndActivatesUserTargeting_withoutTheSwitch() {
        runner.withUserConfiguration(ApplicationRegistry.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(PushDeviceRegistry.class).isSameAs(ApplicationRegistry.REGISTRY);
                    assertThat(context).hasSingleBean(PushDeviceCleanupListener.class);
                    assertThat(context).hasSingleBean(NotificationService.class);
                });
    }

    @Test
    void devicesEnabledWithTheRedisFamily_failsStartup_becauseThereIsNoRedisRegistry() {
        runner.withPropertyValues("notification.push.devices.enabled=true",
                        "notification.redis.push.devices.enabled=true")
                .run(context -> assertThat(context).getFailure()
                        .hasRootCauseMessage("notification.push.devices.enabled=true needs a PushDeviceRegistry:"
                                + " use notification.store.type=memory or jdbc (notification-store-jdbc), or declare a"
                                + " PushDeviceRegistry bean"));
    }

    @Test
    void anInvalidMultiTokenPolicy_failsStartup() {
        runner.withPropertyValues("notification.push.user-targets.multi-token-policy=most")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anApplicationPushRenderer_needsTheSectionsRendererOff() {
        runner.withUserConfiguration(ApplicationPushRenderer.class)
                .run(context -> assertThat(context).hasFailed());
        runner.withUserConfiguration(ApplicationPushRenderer.class)
                .withPropertyValues("notification.push.template-sections.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PushContentRenderer.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ApplicationRegistry {

        static final PushDeviceRegistry REGISTRY = new PushDeviceRegistry() {
            @Override
            public PushDevice register(PushDeviceRegistration registration) {
                return PushDevice.from(registration, null, Instant.now());
            }

            @Override
            public boolean unregister(String userId, String installationId) {
                return false;
            }

            @Override
            public boolean unregisterToken(String token) {
                return false;
            }

            @Override
            public int unregisterAll(String userId) {
                return 0;
            }

            @Override
            public List<PushDevice> activeDevices(String userId, String appId) {
                return List.of();
            }

            @Override
            public int removeByTokenHash(String tokenHash, String reason) {
                return 0;
            }

            @Override
            public int purgeStale(Instant olderThan) {
                return 0;
            }
        };

        @Bean
        PushDeviceRegistry applicationRegistry() {
            return REGISTRY;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ApplicationPushRenderer {

        @Bean
        ChannelContentRenderer applicationPushRenderer() {
            return new ChannelContentRenderer() {
                @Override
                public Channel channel() {
                    return Channel.PUSH;
                }

                @Override
                public RenderedContent render(NotificationRequest request) {
                    return RenderedContent.text("app");
                }
            };
        }
    }
}
