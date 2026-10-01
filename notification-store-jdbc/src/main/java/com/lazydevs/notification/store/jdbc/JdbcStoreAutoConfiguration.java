package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;

/**
 * Registers the JDBC-backed stores when {@code notification.store.type=jdbc}.
 *
 * <p>Ordered before {@code NotificationCoreDefaultsAutoConfiguration} so the
 * in-memory defaults, which are {@code @ConditionalOnMissingBean}, back off.
 * Each store also honours the feature flag the rest of the library uses
 * for it, and backs off when the application defines its own store.
 *
 * <p>No {@code JdbcClient} or {@code ObjectMapper} bean is exposed, so the
 * host's own beans of those types keep injecting unambiguously. The stores
 * use their own {@link JdbcStoreJson} mapper, never the host's, so the
 * stored JSON format is independent of the host's Jackson configuration.
 */
@AutoConfiguration(
        afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        beforeName = "com.lazydevs.notification.core.config.NotificationCoreDefaultsAutoConfiguration")
@ConditionalOnClass(JdbcClient.class)
@ConditionalOnProperty(name = "notification.store.type", havingValue = "jdbc")
@EnableConfigurationProperties(JdbcStoreProperties.class)
@ImportRuntimeHints(JdbcStoreRuntimeHints.class)
public class JdbcStoreAutoConfiguration {

    static final Duration DEFAULT_IDEMPOTENCY_TTL = Duration.ofHours(24);
    static final int DEFAULT_DEAD_LETTER_MAX_ENTRIES = 1_000;
    static final int DEFAULT_DELIVERY_EVENT_MAX_ENTRIES = 5_000;

    @Bean
    @ConditionalOnMissingBean
    public JdbcStoreDataSourceResolver jdbcStoreDataSourceResolver(ListableBeanFactory beanFactory) {
        return new JdbcStoreDataSourceResolver(beanFactory);
    }

    // The per-store feature-flag conditions below may be replaced by a shared
    // @ConditionalOnStoreType condition from the core store-selector refactor.

    @Bean
    @ConditionalOnProperty(prefix = "notification.idempotency", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public JdbcIdempotencyStore jdbcIdempotencyStore(JdbcStoreProperties properties,
                                                     JdbcStoreDataSourceResolver dataSources,
                                                     Environment environment) {
        Duration ttl = Binder.get(environment)
                .bind("notification.idempotency.ttl", Duration.class)
                .orElse(DEFAULT_IDEMPOTENCY_TTL);
        return new JdbcIdempotencyStore(jdbcClient(properties, dataSources), JdbcStoreJson.create(),
                JdbcStoreTables.of(properties), ttl, properties.getPurge().getBatchSize());
    }

    @Bean
    @ConditionalOnProperty(prefix = "notification.dead-letter", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(DeadLetterStore.class)
    public JdbcDeadLetterStore jdbcDeadLetterStore(JdbcStoreProperties properties,
                                                   JdbcStoreDataSourceResolver dataSources,
                                                   Environment environment) {
        int maxEntries = Binder.get(environment)
                .bind("notification.dead-letter.max-entries", Integer.class)
                .orElse(DEFAULT_DEAD_LETTER_MAX_ENTRIES);
        return new JdbcDeadLetterStore(jdbcClient(properties, dataSources), JdbcStoreJson.create(),
                JdbcStoreTables.of(properties), properties.getDeadLetterRetention(), maxEntries);
    }

    @Bean
    @ConditionalOnProperty(prefix = "notification.delivery-events", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(DeliveryEventStore.class)
    public JdbcDeliveryEventStore jdbcDeliveryEventStore(JdbcStoreProperties properties,
                                                         JdbcStoreDataSourceResolver dataSources,
                                                         Environment environment) {
        int maxEntries = Binder.get(environment)
                .bind("notification.delivery-events.max-entries", Integer.class)
                .orElse(DEFAULT_DELIVERY_EVENT_MAX_ENTRIES);
        return new JdbcDeliveryEventStore(jdbcClient(properties, dataSources), JdbcStoreJson.create(),
                JdbcStoreTables.of(properties), properties.getDeliveryEventRetention(), maxEntries);
    }

    @Bean
    @ConditionalOnMissingBean
    public JdbcStorePurger jdbcStorePurger(ObjectProvider<JdbcPurgeableStore> stores,
                                           JdbcStoreProperties properties) {
        return new JdbcStorePurger(stores.orderedStream().toList(), properties.getPurge().getBatchSize());
    }

    @Bean
    @ConditionalOnProperty(prefix = "notification.store.jdbc.purge", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public JdbcStorePurgeScheduler jdbcStorePurgeScheduler(JdbcStorePurger purger,
                                                           JdbcStoreProperties properties) {
        return new JdbcStorePurgeScheduler(purger, properties.getPurge().getInterval());
    }

    private static JdbcClient jdbcClient(JdbcStoreProperties properties, JdbcStoreDataSourceResolver dataSources) {
        return JdbcClient.create(dataSources.resolve(properties.getDatasourceBeanName()));
    }
}
