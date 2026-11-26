package com.lazydevs.notification.starter;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.audit.NotificationAuditStore;
import com.lazydevs.notification.api.util.MessageScrubber;
import com.lazydevs.notification.core.audit.InMemoryNotificationAuditStore;
import com.lazydevs.notification.core.audit.StoreBackedAuditService;
import com.lazydevs.notification.core.service.NoOpAuditService;
import com.lazydevs.notification.core.service.NotificationAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * DD-33 wiring: {@code notification.audit.enabled} selects the audit store of
 * the store family, and the status view is available through
 * {@link NotificationService#find}.
 */
class AuditAutoConfigurationTest {

    private final WebApplicationContextRunner runner = StarterContextRunners.starterRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
            .withUserConfiguration(MockRedis.class);

    @Test
    void auditOff_keepsTheNoOpService_andTheDefaultScrubber() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBean(NotificationAuditService.class).isInstanceOf(NoOpAuditService.class);
            assertThat(context).doesNotHaveBean(NotificationAuditStore.class);
            assertThat(context.getBean(MessageScrubber.class)).isSameAs(MessageScrubber.placeholders());
        });
    }

    @Test
    void auditOnWithTheMemoryFamily_registersTheInMemoryStore() {
        runner.withPropertyValues("notification.audit.enabled=true", "notification.audit.max-entries=50")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(NotificationAuditStore.class)
                            .isInstanceOf(InMemoryNotificationAuditStore.class);
                    assertThat(context).getBean(NotificationAuditService.class)
                            .isInstanceOf(StoreBackedAuditService.class);
                    assertThat(context.getBean(NotificationService.class).find("default", "nope")).isEmpty();
                });
    }

    @Test
    void auditOnWithTheRedisFamily_failsWithoutAStore_unlessAuditIsKeptInMemory() {
        runner.withPropertyValues("notification.audit.enabled=true", "notification.store.type=redis")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("notification.audit.enabled=true needs a NotificationAuditStore");
                });
        runner.withPropertyValues("notification.audit.enabled=true", "notification.store.type=redis",
                        "notification.redis.audit.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(NotificationAuditStore.class)
                            .isInstanceOf(InMemoryNotificationAuditStore.class);
                });
    }

    @Test
    void anApplicationAuditStoreWins() {
        runner.withUserConfiguration(CustomStore.class)
                .withPropertyValues("notification.audit.enabled=true", "notification.store.type=redis")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(NotificationAuditStore.class).isSameAs(CustomStore.STORE);
                });
    }

    @Test
    void anApplicationScrubberWins() {
        runner.withUserConfiguration(CustomScrubber.class)
                .run(context -> assertThat(context.getBean(MessageScrubber.class)).isSameAs(CustomScrubber.SCRUBBER));
    }

    @Configuration(proxyBeanMethods = false)
    static class MockRedis {
        @Bean
        LettuceConnectionFactory redisConnectionFactory() {
            return mock(LettuceConnectionFactory.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomStore {
        static final NotificationAuditStore STORE = mock(NotificationAuditStore.class);

        @Bean
        NotificationAuditStore customAuditStore() {
            return STORE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomScrubber {
        static final MessageScrubber SCRUBBER = message -> null;

        @Bean
        MessageScrubber customScrubber() {
            return SCRUBBER;
        }
    }
}
