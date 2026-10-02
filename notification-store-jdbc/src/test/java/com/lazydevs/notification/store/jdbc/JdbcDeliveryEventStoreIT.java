package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.zaxxer.hikari.HikariDataSource;
import lazydevs.persistence.connection.multitenant.TenantContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class JdbcDeliveryEventStoreIT {

    private static final JdbcStoreTables TABLES = new JdbcStoreTables("notify", "ns_");

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;

    private JdbcDeliveryEventStore store;

    @BeforeAll
    static void startDatabase() {
        postgres = PostgresTestSupport.startPostgres();
        dataSource = PostgresTestSupport.dataSource(postgres);
        jdbc = JdbcClient.create(dataSource);
        // Exercises a custom schema and prefix end to end.
        jdbc.sql("CREATE SCHEMA notify").update();
        PostgresTestSupport.createTables(jdbc, TABLES);
    }

    @AfterAll
    static void stopDatabase() {
        dataSource.close();
        postgres.stop();
    }

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncate(jdbc, TABLES);
        store = newStore(100);
    }

    @AfterEach
    void resetTenant() {
        TenantContext.reset();
    }

    private static JdbcDeliveryEventStore newStore(int maxEntries) {
        return new JdbcDeliveryEventStore(jdbc, PostgresTestSupport.JSON, TABLES, Duration.ofDays(30), maxEntries);
    }

    private static List<String> eventIds(List<DeliveryEvent> events) {
        return events.stream().map(DeliveryEvent::providerEventId).toList();
    }

    @Test
    void addThenFindByProviderMessageIdMostRecentFirst() {
        DeliveryEvent queued = PostgresTestSupport.deliveryEvent("twilio", "SM1", "ev-1", DeliveryStatus.UNKNOWN);
        DeliveryEvent delivered = PostgresTestSupport.deliveryEvent("twilio", "SM1", "ev-2", DeliveryStatus.DELIVERED);
        store.add(queued);
        store.add(delivered);
        store.add(PostgresTestSupport.deliveryEvent("twilio", "SM2", "ev-3", DeliveryStatus.BOUNCED));
        store.add(PostgresTestSupport.deliveryEvent("ses", "SM1", "ev-4", DeliveryStatus.COMPLAINED));

        List<DeliveryEvent> found = store.findByProviderMessageId("twilio", "SM1").orElseThrow();

        assertThat(found).containsExactly(delivered, queued);
        assertThat(found.get(1).attributes()).containsEntry("messagestatus", "unknown");
        assertThat(found.get(1).reason()).isEqualTo("rejected by carrier");
        assertThat(store.findByProviderMessageId("twilio", "missing").orElseThrow()).isEmpty();
        assertThat(store.findByProviderMessageId(null, "SM1").orElseThrow()).isEmpty();
    }

    @Test
    void rowFromANewerVersion_withAnUnknownStatus_readsAsUnknown() {
        store.add(PostgresTestSupport.deliveryEvent("ses", "msg-new", "ev-new", DeliveryStatus.DELIVERED));
        jdbc.sql("UPDATE " + TABLES.deliveryEvent() + " SET status = 'SOMETHING_NEW',"
                + " attributes = '{\"messagestatus\":\"new\",\"futureField\":\"x\"}'"
                + " WHERE provider_message_id = 'msg-new'").update();

        List<DeliveryEvent> found = store.findByProviderMessageId("ses", "msg-new").orElseThrow();

        assertThat(found).singleElement().satisfies(event -> {
            assertThat(event.status()).isEqualTo(DeliveryStatus.UNKNOWN);
            assertThat(event.attributes()).containsEntry("messagestatus", "new");
        });
    }

    @Test
    void onEventBridgesToAdd() {
        store.onEvent(PostgresTestSupport.deliveryEvent("ses", "m-1", "ev-1", DeliveryStatus.DELIVERED));

        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void providerRetriesWithTheSameEventIdAreStoredOnce() {
        store.add(PostgresTestSupport.deliveryEvent("twilio", "SM1", "ev-1", DeliveryStatus.DELIVERED));
        store.add(PostgresTestSupport.deliveryEvent("twilio", "SM1", "ev-1", DeliveryStatus.DELIVERED));
        // No provider event id: nothing to de-duplicate on, so both are kept.
        store.add(PostgresTestSupport.deliveryEvent("twilio", "SM1", null, DeliveryStatus.DELIVERED));
        store.add(PostgresTestSupport.deliveryEvent("twilio", "SM1", null, DeliveryStatus.DELIVERED));

        assertThat(store.size()).isEqualTo(3);
    }

    @Test
    void snapshotIsBoundedAndMostRecentFirst() {
        JdbcDeliveryEventStore bounded = newStore(3);
        for (int i = 0; i < 6; i++) {
            bounded.add(PostgresTestSupport.deliveryEvent("ses", "m-" + i, "ev-" + i, DeliveryStatus.DELIVERED));
        }

        assertThat(eventIds(bounded.snapshot().orElseThrow())).containsExactly("ev-5", "ev-4", "ev-3");
        assertThat(bounded.size()).isEqualTo(6);
        assertThat(bounded.findByProviderMessageId("ses", "m-0").orElseThrow()).hasSize(1);
    }

    @Test
    void tenantColumnComesFromTenantContext() {
        TenantContext.setTenantId("acme");
        store.add(PostgresTestSupport.deliveryEvent("ses", "m-1", "ev-1", DeliveryStatus.DELIVERED));
        TenantContext.reset();
        store.add(PostgresTestSupport.deliveryEvent("ses", "m-2", "ev-2", DeliveryStatus.DELIVERED));

        List<String> tenants = jdbc.sql("SELECT tenant_id FROM " + TABLES.deliveryEvent() + " ORDER BY id")
                .query((rs, n) -> rs.getString(1))
                .list();
        assertThat(tenants).containsExactly("acme", null);
        // size() is global: the current tenant does not narrow it.
        TenantContext.setTenantId("acme");
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void expiredEventsAreHiddenAndPurged() {
        for (int i = 0; i < 4; i++) {
            store.add(PostgresTestSupport.deliveryEvent("ses", "m-" + i, "ev-" + i, DeliveryStatus.DELIVERED));
        }
        PostgresTestSupport.expire(jdbc, TABLES.deliveryEvent(), "provider_event_id IN ('ev-0', 'ev-1', 'ev-2')");

        assertThat(store.size()).isEqualTo(1);
        assertThat(eventIds(store.snapshot().orElseThrow())).containsExactly("ev-3");
        assertThat(store.findByProviderMessageId("ses", "m-0").orElseThrow()).isEmpty();

        assertThat(store.purgeExpired(2)).isEqualTo(2);
        assertThat(store.purgeExpired(2)).isEqualTo(1);
        assertThat(store.purgeExpired(2)).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM " + TABLES.deliveryEvent()).query(Long.class).single())
                .isEqualTo(1L);
    }

    @Test
    void purgerDrainsEveryStore() {
        JdbcDeadLetterStore deadLetters = new JdbcDeadLetterStore(jdbc, PostgresTestSupport.JSON, TABLES,
                Duration.ofDays(30), 100);
        JdbcIdempotencyStore idempotency = new JdbcIdempotencyStore(jdbc, PostgresTestSupport.JSON, TABLES,
                Duration.ofHours(1), 100);
        for (int i = 0; i < 5; i++) {
            store.add(PostgresTestSupport.deliveryEvent("ses", "m-" + i, "ev-" + i, DeliveryStatus.DELIVERED));
            deadLetters.add(PostgresTestSupport.deadLetter("acme", "req-" + i));
            idempotency.markInProgress(new IdempotencyKey("acme", null, "k-" + i), "r-" + i);
        }
        PostgresTestSupport.expire(jdbc, TABLES.deliveryEvent(), "true");
        PostgresTestSupport.expire(jdbc, TABLES.deadLetter(), "request_id <> 'req-0'");
        PostgresTestSupport.expire(jdbc, TABLES.idempotency(), "idem_key = 'k-0'");

        JdbcStorePurger purger = new JdbcStorePurger(List.of(store, deadLetters, idempotency), 2);

        assertThat(purger.purgeAll()).isEqualTo(5 + 4 + 1);
        assertThat(purger.purgeAll()).isZero();
        assertThat(store.size()).isZero();
        assertThat(deadLetters.size()).isEqualTo(1);
    }
}
