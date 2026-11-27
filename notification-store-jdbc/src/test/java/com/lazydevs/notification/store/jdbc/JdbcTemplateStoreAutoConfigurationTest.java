package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.template.TemplateChangeFeed;
import com.lazydevs.notification.api.template.TemplateStore;
import com.lazydevs.notification.core.config.NotificationCoreAutoConfiguration;
import com.lazydevs.notification.core.config.NotificationCoreDefaultsAutoConfiguration;
import com.lazydevs.notification.core.config.NotificationTemplateStoreAutoConfiguration;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import com.lazydevs.notification.core.template.TemplateChangeDispatcher;
import com.lazydevs.notification.core.template.admin.TemplateAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DD-38: wiring of {@link JdbcTemplateStoreAutoConfiguration} with the core. No database is
 * needed: building the store never opens a connection, and the feed's baseline query failing
 * is logged, not fatal.
 */
class JdbcTemplateStoreAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationCoreAutoConfiguration.class,
                    NotificationCoreDefaultsAutoConfiguration.class, JdbcTemplateStoreAutoConfiguration.class,
                    NotificationTemplateStoreAutoConfiguration.class))
            .withUserConfiguration(FailingDataSource.class);

    @Test
    void jdbcTypeRegistersStoreFeedAndAdminService() {
        runner.withPropertyValues("notification.template.store.type=jdbc",
                        "notification.template.legacy-helpers=false",
                        "notification.template.store.poll-interval=2s",
                        "notification.store.jdbc.template.summary-function=notify.template_summaries")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(TemplateStore.class).isInstanceOf(JdbcTemplateStore.class);
                    assertThat(context).getBean(TemplateChangeFeed.class).isInstanceOf(JdbcTemplateChangeFeed.class);
                    assertThat(context.getBean(JdbcTemplateChangeFeed.class).interval()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(context).hasSingleBean(TemplateAdminService.class);
                    assertThat(context).hasSingleBean(TemplateChangeDispatcher.class);
                    assertThat(context.getBean(NotificationTemplateEngine.class).isStoreConfigured()).isTrue();
                });
    }

    @Test
    void jdbcTypeWithLegacyHelpersFailsStartup() {
        runner.withPropertyValues("notification.template.store.type=jdbc")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("notification.template.legacy-helpers=false"));
    }

    @Test
    void invalidFunctionNameFailsStartup() {
        runner.withPropertyValues("notification.template.store.type=jdbc",
                        "notification.template.legacy-helpers=false",
                        "notification.store.jdbc.template.change-function=x; DROP TABLE y")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("notification.store.jdbc.template.change-function"));
    }

    @Test
    void withoutTheTypeNothingIsRegistered() {
        runner.withPropertyValues("notification.store.type=jdbc")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TemplateStore.class);
                    assertThat(context).doesNotHaveBean(TemplateChangeFeed.class);
                    assertThat(context).doesNotHaveBean(TemplateAdminService.class);
                    assertThat(context.getBean(NotificationTemplateEngine.class).isStoreConfigured()).isFalse();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingDataSource {
        @Bean
        DataSource dataSource() throws SQLException {
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenThrow(new SQLException("no database in this test"));
            return dataSource;
        }
    }
}
