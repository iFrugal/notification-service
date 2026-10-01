package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.zaxxer.hikari.HikariDataSource;
import lazydevs.persistence.connection.multitenant.TenantContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The stores run unchanged as a plain, non-owner role under PostgreSQL
 * row-level security keyed on {@code tenant_id}: no superuser, no
 * {@code BYPASSRLS}, no DDL, and each tenant sees only its own rows.
 *
 * <p>The grants below are the complete set the library needs, which is
 * what the module README documents.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcStoreRlsIT {

    private static final JdbcStoreTables TABLES = JdbcStoreTables.defaults();
    private static final String APP_ROLE = "notification_app";
    private static final String APP_PASSWORD = "app-secret";
    private static final Duration LEASE = Duration.ofMinutes(5);

    private static PostgreSQLContainer postgres;
    private static HikariDataSource adminDataSource;
    private static HikariDataSource acmeDataSource;
    private static HikariDataSource globexDataSource;
    private static JdbcClient admin;

    private Stores acme;
    private Stores globex;

    @BeforeAll
    static void startDatabase() {
        postgres = PostgresTestSupport.startPostgres();
        adminDataSource = PostgresTestSupport.dataSource(postgres);
        admin = JdbcClient.create(adminDataSource);
        PostgresTestSupport.createTables(admin, TABLES);

        admin.sql("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "'"
                + " NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE").update();
        for (String table : List.of(TABLES.idempotency(), TABLES.deadLetter(), TABLES.deliveryEvent())) {
            admin.sql("GRANT SELECT, INSERT, UPDATE, DELETE ON " + table + " TO " + APP_ROLE).update();
            admin.sql("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY").update();
            admin.sql("CREATE POLICY tenant_isolation ON " + table
                    + " USING (tenant_id = current_setting('app.tenant', true))"
                    + " WITH CHECK (tenant_id = current_setting('app.tenant', true))").update();
        }

        acmeDataSource = PostgresTestSupport.dataSource(postgres, APP_ROLE, APP_PASSWORD, "SET app.tenant = 'acme'");
        globexDataSource = PostgresTestSupport.dataSource(postgres, APP_ROLE, APP_PASSWORD, "SET app.tenant = 'globex'");
    }

    @AfterAll
    static void stopDatabase() {
        acmeDataSource.close();
        globexDataSource.close();
        adminDataSource.close();
        postgres.stop();
    }

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncate(admin, TABLES);
        acme = new Stores(JdbcClient.create(acmeDataSource));
        globex = new Stores(JdbcClient.create(globexDataSource));
    }

    @AfterEach
    void resetTenant() {
        TenantContext.reset();
    }

    @Test
    void appRoleIsAnUnprivilegedNonOwner() {
        Map<String, Object> role = acme.jdbc.sql(
                        "SELECT rolsuper, rolbypassrls, current_user AS who FROM pg_roles WHERE rolname = current_user")
                .query().singleRow();
        assertThat(role).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false)
                .containsEntry("who", APP_ROLE);
        assertThat(admin.sql("SELECT tableowner FROM pg_tables WHERE tablename = 'notification_dead_letter'")
                .query(String.class).single()).isNotEqualTo(APP_ROLE);
    }

    @Test
    void idempotencyStoreOnlySeesItsTenant() {
        IdempotencyKey acmeKey = new IdempotencyKey("acme", "billing", "shared");
        IdempotencyKey globexKey = new IdempotencyKey("globex", "billing", "shared");

        assertThat(acme.idempotency.markInProgress(acmeKey, "a-1")).isTrue();
        assertThat(acme.idempotency.markInProgress(acmeKey, "a-2")).isFalse();
        assertThat(globex.idempotency.markInProgress(globexKey, "g-1")).isTrue();
        acme.idempotency.markComplete(acmeKey, NotificationResponse.success(
                PostgresTestSupport.request("acme", "a-1"), "smtp", "m-1"));

        assertThat(acme.idempotency.findExisting(acmeKey)).isPresent();
        assertThat(acme.idempotency.findExisting(globexKey)).isEmpty();
        assertThat(globex.idempotency.findExisting(acmeKey)).isEmpty();
        // Writing another tenant's row is refused by the policy, not silently accepted.
        assertThatThrownBy(() -> acme.idempotency.markInProgress(globexKey, "a-3"))
                .isInstanceOf(DataAccessException.class);
        assertThat(countAll(TABLES.idempotency())).isEqualTo(2);
    }

    @Test
    void deadLetterStoreOnlySeesItsTenant() {
        TenantContext.setTenantId("acme");
        for (int i = 0; i < 3; i++) {
            acme.deadLetters.add(PostgresTestSupport.deadLetter("acme", "a-" + i));
        }
        // A cross-tenant write is rejected by WITH CHECK; add() swallows it per the SPI.
        acme.deadLetters.add(PostgresTestSupport.deadLetter("globex", "smuggled"));
        TenantContext.setTenantId("globex");
        globex.deadLetters.add(PostgresTestSupport.deadLetter("globex", "g-0"));
        globex.deadLetters.add(PostgresTestSupport.deadLetter("globex", "g-1"));
        assertThat(countAll(TABLES.deadLetter())).isEqualTo(5);

        TenantContext.setTenantId("acme");
        assertThat(acme.deadLetters.snapshot().orElseThrow())
                .extracting(e -> e.request().getTenantId()).containsOnly("acme").hasSize(3);
        // size() is a global COUNT; only the RLS policy narrows it here.
        assertThat(acme.deadLetters.size()).isEqualTo(3);
        assertThat(acme.deadLetters.findByRequestId("globex", "g-0")).isEmpty();
        assertThat(acme.deadLetters.remove("globex", "g-0")).isFalse();
        assertThat(acme.deadLetters.claim("globex", 10, LEASE)).isEmpty();

        List<DeadLetterEntry> claimed = acme.deadLetters.claim("acme", 10, LEASE);
        assertThat(claimed).extracting(e -> e.request().getRequestId()).containsExactly("a-0", "a-1", "a-2");
        acme.deadLetters.release("acme", "a-1");
        assertThat(acme.deadLetters.remove("acme", "a-0")).isTrue();
        assertThat(acme.deadLetters.claim("acme", 10, LEASE))
                .extracting(e -> e.request().getRequestId()).containsExactly("a-1");

        TenantContext.setTenantId("globex");
        assertThat(globex.deadLetters.size()).isEqualTo(2);
        assertThat(globex.deadLetters.claim("globex", 10, LEASE)).hasSize(2);
        assertThat(countAll(TABLES.deadLetter())).isEqualTo(4);
    }

    @Test
    void deliveryEventStoreOnlySeesItsTenant() {
        TenantContext.setTenantId("acme");
        acme.deliveryEvents.add(PostgresTestSupport.deliveryEvent("ses", "m-a", "ev-a", DeliveryStatus.DELIVERED));
        TenantContext.setTenantId("globex");
        globex.deliveryEvents.add(PostgresTestSupport.deliveryEvent("ses", "m-g", "ev-g", DeliveryStatus.BOUNCED));
        // Same provider event id in another tenant is a different event.
        globex.deliveryEvents.add(PostgresTestSupport.deliveryEvent("ses", "m-a", "ev-a", DeliveryStatus.DELIVERED));

        assertThat(acme.deliveryEvents.size()).isEqualTo(1);
        assertThat(acme.deliveryEvents.snapshot().orElseThrow()).extracting("providerMessageId").containsExactly("m-a");
        assertThat(acme.deliveryEvents.findByProviderMessageId("ses", "m-g").orElseThrow()).isEmpty();
        assertThat(globex.deliveryEvents.size()).isEqualTo(2);
        assertThat(countAll(TABLES.deliveryEvent())).isEqualTo(3);
    }

    @Test
    void purgeUnderRlsOnlyDeletesVisibleRows() {
        TenantContext.setTenantId("acme");
        acme.deadLetters.add(PostgresTestSupport.deadLetter("acme", "a-0"));
        acme.deliveryEvents.add(PostgresTestSupport.deliveryEvent("ses", "m-a", "ev-a", DeliveryStatus.DELIVERED));
        acme.idempotency.markInProgress(new IdempotencyKey("acme", null, "k"), "a-0");
        TenantContext.setTenantId("globex");
        globex.deadLetters.add(PostgresTestSupport.deadLetter("globex", "g-0"));
        globex.deliveryEvents.add(PostgresTestSupport.deliveryEvent("ses", "m-g", "ev-g", DeliveryStatus.DELIVERED));
        globex.idempotency.markInProgress(new IdempotencyKey("globex", null, "k"), "g-0");
        for (String table : List.of(TABLES.idempotency(), TABLES.deadLetter(), TABLES.deliveryEvent())) {
            PostgresTestSupport.expire(admin, table, "true");
        }

        int purged = new JdbcStorePurger(List.of(acme.idempotency, acme.deadLetters, acme.deliveryEvents), 10)
                .purgeAll();

        assertThat(purged).isEqualTo(3);
        for (String table : List.of(TABLES.idempotency(), TABLES.deadLetter(), TABLES.deliveryEvent())) {
            assertThat(admin.sql("SELECT tenant_id FROM " + table).query(String.class).list())
                    .as(table).containsExactly("globex");
        }
    }

    private static long countAll(String table) {
        return admin.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    /** The three stores bound to one tenant's connection pool. */
    private static final class Stores {
        final JdbcClient jdbc;
        final JdbcIdempotencyStore idempotency;
        final JdbcDeadLetterStore deadLetters;
        final JdbcDeliveryEventStore deliveryEvents;

        Stores(JdbcClient jdbc) {
            this.jdbc = jdbc;
            this.idempotency = new JdbcIdempotencyStore(jdbc, PostgresTestSupport.JSON, TABLES, Duration.ofHours(1), 10);
            this.deadLetters = new JdbcDeadLetterStore(jdbc, PostgresTestSupport.JSON, TABLES, Duration.ofDays(1), 100);
            this.deliveryEvents = new JdbcDeliveryEventStore(jdbc, PostgresTestSupport.JSON, TABLES,
                    Duration.ofDays(1), 100);
        }
    }
}
