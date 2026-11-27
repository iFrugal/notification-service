package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.template.TemplateChangeListener;
import com.lazydevs.notification.api.template.TemplateFilter;
import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.api.template.TemplateSummary;
import com.lazydevs.notification.api.template.TemplateVersion;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DD-38: the template store under PostgreSQL row-level security, with the policy shape of a
 * pooled multi-tenant host: a session tenant in {@code app.tenant_id}, platform defaults
 * ({@code tenant_id IS NULL}) readable by every tenant and writable only by the
 * {@code '~pool'} session. A plain, non-owner role; the cross-tenant listing and the change
 * feed go through {@code SECURITY DEFINER} functions that return keys and metadata only.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcTemplateStoreRlsIT {

    private static final JdbcStoreTables TABLES = JdbcStoreTables.defaults();
    private static final String APP_ROLE = "notification_app";
    private static final String APP_PASSWORD = "app-secret";
    private static final String SUMMARY_FUNCTION = "notification_template_summaries";
    private static final String CHANGE_FUNCTION = "notification_template_changes";

    private static final TemplateKey DEFAULT = TemplateKey.of(null, "email", "WELCOME");
    private static final TemplateKey ACME = TemplateKey.of("acme", "email", "WELCOME");
    private static final TemplateKey GLOBEX = TemplateKey.of("globex", "email", "WELCOME");
    private static final String FTL = TemplateVersion.FREEMARKER;

    private static PostgreSQLContainer postgres;
    private static HikariDataSource adminDataSource;
    private static HikariDataSource poolDataSource;
    private static HikariDataSource acmeDataSource;
    private static HikariDataSource globexDataSource;
    private static JdbcClient admin;

    @BeforeAll
    static void startDatabase() {
        postgres = PostgresTestSupport.startPostgres();
        adminDataSource = PostgresTestSupport.dataSource(postgres);
        admin = JdbcClient.create(adminDataSource);
        PostgresTestSupport.createTables(admin, TABLES);

        admin.sql("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "'"
                + " NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE").update();
        String tenant = "nullif(current_setting('app.tenant_id', true), '~pool')";
        String writable = "tenant_id = " + tenant
                + " OR (tenant_id IS NULL AND current_setting('app.tenant_id', true) = '~pool')";
        for (String table : List.of(TABLES.templateVersion(), TABLES.templateEvent())) {
            admin.sql("GRANT SELECT, INSERT, UPDATE, DELETE ON " + table + " TO " + APP_ROLE).update();
            admin.sql("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY").update();
            admin.sql("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY").update();
            admin.sql("CREATE POLICY template_select ON " + table + " FOR SELECT TO " + APP_ROLE
                    + " USING (tenant_id = " + tenant + " OR tenant_id IS NULL)").update();
            admin.sql("CREATE POLICY template_write ON " + table + " FOR ALL TO " + APP_ROLE
                    + " USING (" + writable + ") WITH CHECK (" + writable + ")").update();
        }
        admin.sql("""
                CREATE FUNCTION %s(p_channel varchar, p_template_id varchar)
                RETURNS TABLE (tenant_id varchar, channel varchar, template_id varchar, locale varchar,
                               version integer, status varchar, content_type varchar, revision bigint,
                               created_by varchar, created_at timestamptz, updated_by varchar,
                               updated_at timestamptz, published_by varchar, published_at timestamptz, note text,
                               provider_status varchar, provider_ref varchar, provider_reason text)
                LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
                  SELECT v.tenant_id, v.channel, v.template_id, v.locale, v.version, v.status, v.content_type,
                         v.revision, v.created_by, v.created_at, v.updated_by, v.updated_at, v.published_by,
                         v.published_at, v.note, v.provider_status, v.provider_ref, v.provider_reason
                  FROM %s v
                  WHERE v.status IN ('DRAFT', 'PUBLISHED')
                    AND (p_channel IS NULL OR v.channel = p_channel)
                    AND (p_template_id IS NULL OR v.template_id = p_template_id)
                $$""".formatted(SUMMARY_FUNCTION, TABLES.templateVersion())).update();
        admin.sql("""
                CREATE FUNCTION %s(p_after bigint, p_limit integer)
                RETURNS TABLE (id bigint, tenant_id varchar, channel varchar, template_id varchar, locale varchar)
                LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
                  SELECT e.id, e.tenant_id, e.channel, e.template_id, e.locale FROM %s e
                  WHERE e.id > p_after ORDER BY e.id LIMIT p_limit
                $$""".formatted(CHANGE_FUNCTION, TABLES.templateEvent())).update();

        poolDataSource = session("~pool");
        acmeDataSource = session("acme");
        globexDataSource = session("globex");
    }

    private static HikariDataSource session(String tenant) {
        return PostgresTestSupport.dataSource(postgres, APP_ROLE, APP_PASSWORD, "SET app.tenant_id = '" + tenant + "'");
    }

    @AfterAll
    static void stopDatabase() {
        poolDataSource.close();
        acmeDataSource.close();
        globexDataSource.close();
        adminDataSource.close();
        postgres.stop();
    }

    private JdbcTemplateStore pool;
    private JdbcTemplateStore acme;
    private JdbcTemplateStore globex;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncate(admin, TABLES);
        pool = new JdbcTemplateStore(poolDataSource, TABLES, 10_000, null);
        acme = new JdbcTemplateStore(acmeDataSource, TABLES, 10_000, null);
        globex = new JdbcTemplateStore(globexDataSource, TABLES, 10_000, null);
    }

    private static void publish(JdbcTemplateStore store, TemplateKey key, String content) {
        long revision = store.findDraft(key).map(TemplateVersion::revision).orElse(0L);
        TemplateVersion draft = store.saveDraft(key, FTL, content, null, revision, "staff", null);
        store.publish(key, draft.revision(), "staff", "n");
    }

    @Test
    void onlyThePoolSessionWritesDefaultsAndTenantsWriteTheirOwnRows() {
        publish(pool, DEFAULT, "default v1");
        publish(acme, ACME, "acme v1");

        assertThatThrownBy(() -> acme.saveDraft(DEFAULT, FTL, "hijack", null, 0, "staff", null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> acme.saveDraft(GLOBEX, FTL, "smuggled", null, 0, "staff", null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> pool.saveDraft(ACME, FTL, "pool cannot", null, 0, "staff", null))
                .isInstanceOf(DataAccessException.class);
        assertThat(admin.sql("SELECT COUNT(*) FROM " + TABLES.templateVersion()).query(Long.class).single())
                .isEqualTo(2);
    }

    @Test
    void tenantsReadTheirOwnRowsAndTheDefaultsOnly() {
        publish(pool, DEFAULT, "default v1");
        publish(acme, ACME, "acme v1");
        publish(globex, GLOBEX, "globex v1");

        assertThat(acme.findPublished(DEFAULT)).map(TemplateVersion::content).contains("default v1");
        assertThat(acme.findPublished(ACME)).map(TemplateVersion::content).contains("acme v1");
        assertThat(acme.findPublished(GLOBEX)).isEmpty();
        assertThat(acme.versions(GLOBEX)).isEmpty();
        assertThat(acme.events(GLOBEX, 10)).isEmpty();
        assertThat(pool.findPublished(ACME)).isEmpty();

        assertThat(acme.summaries(TemplateFilter.all())).extracting(s -> s.key().tenantId())
                .containsExactlyInAnyOrder(null, "acme");
    }

    @Test
    void summaryFunctionListsEveryTenantWithoutContent() {
        publish(pool, DEFAULT, "default v1");
        publish(acme, ACME, "acme v1");
        TemplateVersion globexDraft = globex.saveDraft(GLOBEX, FTL, "globex draft", null, 0, "staff", null);
        JdbcTemplateStore listing = new JdbcTemplateStore(poolDataSource, TABLES, 10_000, SUMMARY_FUNCTION);

        List<TemplateSummary> all = listing.summaries(new TemplateFilter("email", "WELCOME"));

        assertThat(all).extracting(TemplateSummary::key).containsExactly(DEFAULT, ACME, GLOBEX);
        assertThat(all.get(1).published().content()).isNull();
        assertThat(all.get(2).draft().revision()).isEqualTo(globexDraft.revision());
        assertThat(listing.summaries(new TemplateFilter("sms", null))).isEmpty();
    }

    @Test
    void changeFunctionLetsTheFeedSeeEveryTenantsChanges() {
        JdbcTemplateChangeFeed narrow = feed(null);
        JdbcTemplateChangeFeed wide = feed(CHANGE_FUNCTION);
        List<TemplateKey> narrowSeen = new ArrayList<>();
        List<TemplateKey> wideSeen = new ArrayList<>();
        narrow.subscribe(narrowSeen::add);
        wide.subscribe(new Recorder(wideSeen));
        narrow.baseline();
        wide.baseline();

        publish(pool, DEFAULT, "default v1");
        publish(globex, GLOBEX, "globex v1");
        narrow.poll();
        wide.poll();

        assertThat(narrowSeen).containsOnly(DEFAULT);
        assertThat(wideSeen).containsOnly(DEFAULT, GLOBEX);
    }

    private static JdbcTemplateChangeFeed feed(String function) {
        return new JdbcTemplateChangeFeed(JdbcClient.create(acmeDataSource), TABLES, Duration.ofSeconds(1), function);
    }

    private record Recorder(List<TemplateKey> seen) implements TemplateChangeListener {
        @Override
        public void templateChanged(TemplateKey key) {
            seen.add(key);
        }
    }
}
