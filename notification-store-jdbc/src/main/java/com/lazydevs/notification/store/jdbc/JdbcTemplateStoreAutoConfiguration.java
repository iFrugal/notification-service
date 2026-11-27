package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.template.TemplateChangeFeed;
import com.lazydevs.notification.api.template.TemplateStore;
import com.lazydevs.notification.api.template.TemplateStoreChecks;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Registers the JDBC template store and its polling change feed when
 * {@code notification.template.store.type=jdbc} (DD-38), independently of
 * {@code notification.store.type}. Uses the same schema, table prefix and DataSource as the
 * other JDBC stores ({@code notification.store.jdbc.*}).
 *
 * <p>Ordered before the core defaults and the core template store wiring, which only
 * activates when a {@link TemplateStore} bean exists.
 */
@AutoConfiguration(
        afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        beforeName = {"com.lazydevs.notification.core.config.NotificationCoreDefaultsAutoConfiguration",
                "com.lazydevs.notification.core.config.NotificationTemplateStoreAutoConfiguration"})
@ConditionalOnClass(JdbcClient.class)
@ConditionalOnProperty(name = "notification.template.store.type", havingValue = "jdbc")
@EnableConfigurationProperties(JdbcStoreProperties.class)
public class JdbcTemplateStoreAutoConfiguration {

    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(15);

    @Bean
    @ConditionalOnMissingBean
    public JdbcStoreDataSourceResolver jdbcStoreDataSourceResolver(ListableBeanFactory beanFactory) {
        return new JdbcStoreDataSourceResolver(beanFactory);
    }

    @Bean
    @ConditionalOnMissingBean(TemplateStore.class)
    public JdbcTemplateStore jdbcTemplateStore(JdbcStoreProperties properties, JdbcStoreDataSourceResolver dataSources,
                                               Environment environment) {
        int maxBytes = Math.toIntExact(Binder.get(environment)
                .bind("notification.template.store.max-content-size", DataSize.class)
                .map(DataSize::toBytes)
                .orElse((long) TemplateStoreChecks.DEFAULT_MAX_CONTENT_BYTES));
        return new JdbcTemplateStore(dataSources.resolve(properties.getDatasourceBeanName()),
                JdbcStoreTables.of(properties), maxBytes, properties.getTemplate().getSummaryFunction());
    }

    @Bean
    @ConditionalOnMissingBean(TemplateChangeFeed.class)
    public JdbcTemplateChangeFeed jdbcTemplateChangeFeed(JdbcStoreProperties properties,
                                                         JdbcStoreDataSourceResolver dataSources,
                                                         Environment environment) {
        Duration interval = Binder.get(environment)
                .bind("notification.template.store.poll-interval", Duration.class)
                .orElse(DEFAULT_POLL_INTERVAL);
        return new JdbcTemplateChangeFeed(JdbcClient.create(dataSources.resolve(properties.getDatasourceBeanName())),
                JdbcStoreTables.of(properties), interval, properties.getTemplate().getChangeFunction());
    }
}
