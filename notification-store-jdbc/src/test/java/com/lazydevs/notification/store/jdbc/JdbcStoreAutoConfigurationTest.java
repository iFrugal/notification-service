package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.core.config.NotificationCoreDefaultsAutoConfiguration;
import com.lazydevs.notification.core.deadletter.InMemoryDeadLetterStore;
import com.lazydevs.notification.core.idempotency.CaffeineIdempotencyStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wiring of {@link JdbcStoreAutoConfiguration}. No database is needed:
 * building the stores never opens a connection, and the DataSource
 * selection is observed through which mock a query reaches.
 */
class JdbcStoreAutoConfigurationTest {

    private static final String[] ALL_FEATURES = {
            "notification.store.type=jdbc",
            "notification.dead-letter.enabled=true",
            "notification.delivery-events.enabled=true"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JdbcStoreAutoConfiguration.class));

    @Test
    void storeTypeJdbcWithADataSourceRegistersTheThreeStoresAndThePurger() {
        runner.withUserConfiguration(SingleDataSource.class)
                .withPropertyValues(ALL_FEATURES)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(IdempotencyStore.class).isInstanceOf(JdbcIdempotencyStore.class);
                    assertThat(context).getBean(DeadLetterStore.class).isInstanceOf(JdbcDeadLetterStore.class);
                    assertThat(context).getBean(DeliveryEventStore.class).isInstanceOf(JdbcDeliveryEventStore.class);
                    // The delivery store joins the webhook listener fan-out.
                    assertThat(context.getBeansOfType(DeliveryEventListener.class)).hasSize(1);
                    assertThat(context.getBean(JdbcStorePurger.class).stores()).hasSize(3);
                    assertThat(context).doesNotHaveBean(JdbcStorePurgeScheduler.class);
                    assertThat(context.getBean(JdbcStoreProperties.class).getTablePrefix()).isEqualTo("notification_");
                });
    }

    @Test
    void storeTypeUnsetRegistersNothing() {
        runner.withUserConfiguration(SingleDataSource.class)
                .withPropertyValues("notification.dead-letter.enabled=true", "notification.delivery-events.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(IdempotencyStore.class);
                    assertThat(context).doesNotHaveBean(DeadLetterStore.class);
                    assertThat(context).doesNotHaveBean(DeliveryEventStore.class);
                    assertThat(context).doesNotHaveBean(JdbcStorePurger.class);
                    assertThat(context).doesNotHaveBean(JdbcStoreProperties.class);
                });
    }

    @Test
    void featureFlagsGateEachStore() {
        runner.withUserConfiguration(SingleDataSource.class)
                .withPropertyValues("notification.store.type=jdbc")
                .run(context -> {
                    // Idempotency is on unless disabled; dead letters and delivery events are opt-in.
                    assertThat(context).getBean(IdempotencyStore.class).isInstanceOf(JdbcIdempotencyStore.class);
                    assertThat(context).doesNotHaveBean(DeadLetterStore.class);
                    assertThat(context).doesNotHaveBean(DeliveryEventStore.class);
                    assertThat(context.getBean(JdbcStorePurger.class).stores()).hasSize(1);
                });
        runner.withUserConfiguration(SingleDataSource.class)
                .withPropertyValues("notification.store.type=jdbc", "notification.idempotency.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IdempotencyStore.class));
    }

    @Test
    void anApplicationDefinedStoreWins() {
        runner.withUserConfiguration(SingleDataSource.class, CustomDeadLetterStore.class)
                .withPropertyValues(ALL_FEATURES)
                .run(context -> {
                    assertThat(context).getBean(DeadLetterStore.class).isSameAs(CustomDeadLetterStore.STORE);
                    assertThat(context.getBean(JdbcStorePurger.class).stores()).hasSize(2);
                });
    }

    @Test
    void orderedBeforeTheCoreDefaultsSoTheInMemoryStoresBackOff() {
        ApplicationContextRunner withCore = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        NotificationCoreDefaultsAutoConfiguration.class, JdbcStoreAutoConfiguration.class))
                .withUserConfiguration(SingleDataSource.class);

        withCore.withPropertyValues("notification.store.type=jdbc", "notification.dead-letter.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IdempotencyStore.class);
                    assertThat(context).getBean(IdempotencyStore.class).isInstanceOf(JdbcIdempotencyStore.class);
                    assertThat(context).hasSingleBean(DeadLetterStore.class);
                    assertThat(context).getBean(DeadLetterStore.class).isInstanceOf(JdbcDeadLetterStore.class);
                });
        // Control: without store.type=jdbc the core defaults are used.
        withCore.withPropertyValues("notification.dead-letter.enabled=true")
                .run(context -> {
                    assertThat(context).getBean(IdempotencyStore.class).isInstanceOf(CaffeineIdempotencyStore.class);
                    assertThat(context).getBean(DeadLetterStore.class).isInstanceOf(InMemoryDeadLetterStore.class);
                });
    }

    @Test
    void namedDataSourceIsUsedWhenConfigured() {
        runner.withUserConfiguration(TwoDataSources.class)
                .withPropertyValues("notification.store.type=jdbc", "notification.store.jdbc.datasource-bean-name=storeDs")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IdempotencyStore store = context.getBean(IdempotencyStore.class);
                    assertThatThrownBy(() -> store.findExisting(new IdempotencyKey("t", null, "k")))
                            .rootCause().hasMessage("reached storeDs");
                });
    }

    @Test
    void severalDataSourcesWithoutANameFailWithAClearMessage() {
        runner.withUserConfiguration(TwoDataSources.class)
                .withPropertyValues("notification.store.type=jdbc")
                .run(context -> assertThat(resolverFailure(context.getStartupFailure()))
                        .hasMessageContaining("notification.store.jdbc.datasource-bean-name"));
    }

    @Test
    void unknownDataSourceNameFailsWithAClearMessage() {
        runner.withUserConfiguration(SingleDataSource.class)
                .withPropertyValues("notification.store.type=jdbc", "notification.store.jdbc.datasource-bean-name=nope")
                .run(context -> assertThat(resolverFailure(context.getStartupFailure()))
                        .hasMessageContaining("no DataSource bean named 'nope'"));
    }

    @Test
    void missingDataSourceFailsWithAClearMessage() {
        runner.withPropertyValues("notification.store.type=jdbc")
                .run(context -> assertThat(resolverFailure(context.getStartupFailure()))
                        .hasMessageContaining("requires a DataSource bean"));
    }

    @Test
    void purgeEnabledRegistersAndStartsTheSchedule() {
        runner.withUserConfiguration(SingleDataSource.class)
                .withPropertyValues(ALL_FEATURES)
                .withPropertyValues("notification.store.jdbc.purge.enabled=true",
                        "notification.store.jdbc.purge.interval=PT5M")
                .run(context -> {
                    JdbcStorePurgeScheduler scheduler = context.getBean(JdbcStorePurgeScheduler.class);
                    assertThat(scheduler.interval()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(scheduler.isRunning()).isTrue();
                });
    }

    @Test
    void invalidSettingsFailStartupNamingTheProperty() {
        runner.withUserConfiguration(SingleDataSource.class)
                .withPropertyValues(ALL_FEATURES)
                .withPropertyValues("notification.idempotency.ttl=0s")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("notification.idempotency.ttl"));
        runner.withUserConfiguration(SingleDataSource.class)
                .withPropertyValues(ALL_FEATURES)
                .withPropertyValues("notification.store.jdbc.table-prefix=bad-prefix")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("table-prefix"));
    }

    /** The resolver's own exception, wherever Spring wrapped it in the startup failure. */
    private static Throwable resolverFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IllegalStateException && t.getMessage().startsWith("notification.store.type=jdbc")) {
                return t;
            }
        }
        throw new AssertionError("no JdbcStoreDataSourceResolver failure in " + failure, failure);
    }

    private static DataSource failingDataSource(String name) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("reached " + name));
        return dataSource;
    }

    @Configuration(proxyBeanMethods = false)
    static class SingleDataSource {
        @Bean
        DataSource dataSource() throws SQLException {
            return failingDataSource("dataSource");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoDataSources {
        @Bean
        DataSource appDs() throws SQLException {
            return failingDataSource("appDs");
        }

        @Bean
        DataSource storeDs() throws SQLException {
            return failingDataSource("storeDs");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomDeadLetterStore {
        static final DeadLetterStore STORE = mock(DeadLetterStore.class);

        @Bean
        DeadLetterStore customDeadLetterStore() {
            return STORE;
        }
    }
}
