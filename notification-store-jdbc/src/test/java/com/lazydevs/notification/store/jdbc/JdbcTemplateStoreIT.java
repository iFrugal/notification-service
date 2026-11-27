package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.template.TemplateApprovalStatus;
import com.lazydevs.notification.api.template.TemplateEvent;
import com.lazydevs.notification.api.template.TemplateEventAction;
import com.lazydevs.notification.api.template.TemplateFilter;
import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.api.template.TemplateStoreException;
import com.lazydevs.notification.api.template.TemplateStoreException.Reason;
import com.lazydevs.notification.api.template.TemplateSummary;
import com.lazydevs.notification.api.template.TemplateVersion;
import com.lazydevs.notification.api.template.TemplateVersionStatus;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * DD-38: the JDBC template store on PostgreSQL: the draft, publish, restore, discard and reset
 * lifecycle, the size limit, and per-key serialisation of concurrent writes.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcTemplateStoreIT {

    private static final JdbcStoreTables TABLES = JdbcStoreTables.defaults();
    private static final TemplateKey KEY = TemplateKey.of(null, "email", "WELCOME");
    private static final TemplateKey ACME = TemplateKey.of("acme", "email", "WELCOME");
    private static final String FTL = TemplateVersion.FREEMARKER;

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;

    private JdbcTemplateStore store;

    @BeforeAll
    static void startDatabase() {
        postgres = PostgresTestSupport.startPostgres();
        dataSource = PostgresTestSupport.dataSource(postgres);
        jdbc = JdbcClient.create(dataSource);
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
        store = new JdbcTemplateStore(dataSource, TABLES, 64, null);
    }

    @Test
    void draftRevisionsAndSizeLimit() {
        TemplateVersion first = store.saveDraft(KEY, FTL, "a", "{\"x\":1}", 0, "ann", "start");
        assertThat(first.revision()).isPositive();
        assertThat(first.status()).isEqualTo(TemplateVersionStatus.DRAFT);
        assertThat(first.sampleModel()).isEqualTo("{\"x\":1}");
        assertThat(first.createdAt()).isNotNull();

        TemplateVersion second = store.saveDraft(KEY, FTL, "b", null, first.revision(), "bob", null);
        assertThat(second.revision()).isGreaterThan(first.revision());
        assertThat(second.createdBy()).isEqualTo("ann");
        assertThat(second.updatedBy()).isEqualTo("bob");
        assertThat(store.findDraft(KEY)).map(TemplateVersion::content).contains("b");

        TemplateStoreException stale = catchThrowableOfType(TemplateStoreException.class,
                () -> store.saveDraft(KEY, FTL, "c", null, first.revision(), "ann", null));
        assertThat(stale.getReason()).isEqualTo(Reason.REVISION_CONFLICT);
        assertThat(stale.getCurrentRevision()).isEqualTo(second.revision());

        assertThat(catchThrowableOfType(TemplateStoreException.class,
                () -> store.saveDraft(KEY, FTL, "é".repeat(33), null, second.revision(), "ann", null)).getReason())
                .isEqualTo(Reason.SIZE_LIMIT);
        assertThat(store.events(KEY, 10)).extracting(TemplateEvent::action)
                .containsExactly(TemplateEventAction.UPDATED_DRAFT, TemplateEventAction.CREATED_DRAFT);
    }

    @Test
    void publishRestoreResetLifecycle() {
        TemplateVersion draft1 = store.saveDraft(KEY, FTL, "v1", "{}", 0, "ann", null);
        TemplateVersion v1 = store.publish(KEY, draft1.revision(), "ann", "first");
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.publishedAt()).isNotNull();
        assertThat(store.findDraft(KEY)).isEmpty();

        TemplateVersion draft2 = store.saveDraft(KEY, FTL, "v2", null, 0, "bob", null);
        assertThat(draft2.revision()).as("never reused after a publish").isGreaterThan(v1.revision());
        assertThat(catchThrowableOfType(TemplateStoreException.class,
                () -> store.publish(KEY, v1.revision(), "bob", "x")).getReason()).isEqualTo(Reason.REVISION_CONFLICT);
        assertThat(store.publish(KEY, draft2.revision(), "bob", "second").version()).isEqualTo(2);

        TemplateVersion v3 = store.restore(KEY, 1, "cat", "back");
        assertThat(v3.version()).isEqualTo(3);
        assertThat(v3.content()).isEqualTo("v1");
        assertThat(v3.sampleModel()).isEqualTo("{}");
        assertThat(store.findPublished(KEY)).map(TemplateVersion::version).contains(3);
        assertThat(store.versions(KEY)).extracting(TemplateVersion::version, TemplateVersion::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(3, TemplateVersionStatus.PUBLISHED),
                        org.assertj.core.groups.Tuple.tuple(2, TemplateVersionStatus.SUPERSEDED),
                        org.assertj.core.groups.Tuple.tuple(1, TemplateVersionStatus.SUPERSEDED));

        store.archive(KEY, "cat", "reset");
        assertThat(store.findPublished(KEY)).isEmpty();
        assertThat(catchThrowableOfType(TemplateStoreException.class, () -> store.archive(KEY, "cat", null))
                .getReason()).isEqualTo(Reason.NO_OVERRIDE);
        assertThat(catchThrowableOfType(TemplateStoreException.class, () -> store.restore(KEY, 42, "cat", null))
                .getReason()).isEqualTo(Reason.UNKNOWN_VERSION);
        assertThat(catchThrowableOfType(TemplateStoreException.class, () -> store.discardDraft(KEY, "cat", null))
                .getReason()).isEqualTo(Reason.NO_DRAFT);

        assertThat(store.events(KEY, 3)).extracting(TemplateEvent::action, TemplateEvent::version)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(TemplateEventAction.RESET, 3),
                        org.assertj.core.groups.Tuple.tuple(TemplateEventAction.RESTORED, 3),
                        org.assertj.core.groups.Tuple.tuple(TemplateEventAction.PUBLISHED, 2));
    }

    @Test
    void tenantAndDefaultLayersAreSeparateKeys() {
        TemplateVersion draftOfKey = store.saveDraft(KEY, FTL, "default", null, 0, "ann", null);
        store.publish(KEY, draftOfKey.revision(), "ann", "n");
        store.saveDraft(ACME, FTL, "acme", null, 0, "ann", null);
        store.saveDraft(TemplateKey.of(null, "sms", "OTP"), FTL, "otp", null, 0, "ann", null);

        assertThat(store.findPublished(ACME)).isEmpty();
        assertThat(store.findDraft(ACME)).map(TemplateVersion::content).contains("acme");

        List<TemplateSummary> email = store.summaries(new TemplateFilter("email", null));
        assertThat(email).extracting(TemplateSummary::key).containsExactly(KEY, ACME);
        assertThat(email.getFirst().published().content()).isNull();
        assertThat(email.getFirst().draft()).isNull();
        assertThat(email.get(1).draft().revision()).isPositive();
        assertThat(store.summaries(TemplateFilter.all())).hasSize(3);
        assertThat(store.summaries(new TemplateFilter(null, "OTP"))).hasSize(1);
    }

    @Test
    void approvalIsRecordedAndCopiedOnRestore() {
        TemplateKey whatsapp = TemplateKey.of(null, "whatsapp", "ORDER");
        TemplateVersion draftOfWhatsapp = store.saveDraft(whatsapp, TemplateVersion.JSON, "{}", null, 0, "ann", null);
        store.publish(whatsapp, draftOfWhatsapp.revision(), "ann", "n");

        TemplateVersion approved = store.updateApproval(whatsapp, 1, TemplateApprovalStatus.APPROVED, "meta-1", null,
                "sys");
        assertThat(approved.approvalStatus()).isEqualTo(TemplateApprovalStatus.APPROVED);
        assertThat(store.restore(whatsapp, 1, "ann", null).providerRef()).isEqualTo("meta-1");
        assertThat(catchThrowableOfType(TemplateStoreException.class, () -> store.updateApproval(whatsapp, 7,
                TemplateApprovalStatus.REJECTED, null, "bad", "sys")).getReason()).isEqualTo(Reason.UNKNOWN_VERSION);
        assertThat(store.events(whatsapp, 2).get(1).note()).isEqualTo("APPROVED");
    }

    @Test
    void aStaleRevisionNeverMatchesALaterDraft() {
        TemplateVersion read = store.saveDraft(KEY, FTL, "a", null, 0, "ann", null);
        store.discardDraft(KEY, "bob", "start over");
        TemplateVersion replacement = store.saveDraft(KEY, FTL, "b", null, 0, "bob", null);

        assertThat(replacement.revision()).isGreaterThan(read.revision());
        assertThat(catchThrowableOfType(TemplateStoreException.class,
                () -> store.saveDraft(KEY, FTL, "a2", null, read.revision(), "ann", null)).getReason())
                .isEqualTo(Reason.REVISION_CONFLICT);
        assertThat(catchThrowableOfType(TemplateStoreException.class,
                () -> store.publish(KEY, read.revision(), "ann", "n")).getReason())
                .isEqualTo(Reason.REVISION_CONFLICT);
        assertThat(store.findDraft(KEY)).map(TemplateVersion::content).contains("b");
    }

    @Test
    void concurrentRestoresGetConsecutiveVersions() throws Exception {
        TemplateVersion draftOfKey = store.saveDraft(KEY, FTL, "v1", null, 0, "ann", null);
        store.publish(KEY, draftOfKey.revision(), "ann", "n");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> restores = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                restores.add(() -> store.restore(KEY, 1, "racer", null).version());
            }
            List<Integer> versions = new ArrayList<>();
            for (Future<Integer> future : pool.invokeAll(restores)) {
                versions.add(future.get());
            }
            assertThat(versions).containsExactlyInAnyOrderElementsOf(
                    java.util.stream.IntStream.rangeClosed(2, 17).boxed().toList());
        } finally {
            pool.shutdownNow();
        }
        assertThat(store.findPublished(KEY)).map(TemplateVersion::version).contains(17);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM " + TABLES.templateVersion() + " WHERE status = 'PUBLISHED'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentPublishesOfOneDraftPublishItOnce() throws Exception {
        TemplateVersion draft = store.saveDraft(KEY, FTL, "v1", null, 0, "ann", null);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        int published = 0;
        int refused = 0;
        try {
            List<Callable<TemplateVersion>> publishes = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                publishes.add(() -> store.publish(KEY, draft.revision(), "racer", "n"));
            }
            for (Future<TemplateVersion> future : pool.invokeAll(publishes)) {
                try {
                    future.get();
                    published++;
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(TemplateStoreException.class);
                    assertThat(((TemplateStoreException) e.getCause()).getReason()).isEqualTo(Reason.NO_DRAFT);
                    refused++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(published).isEqualTo(1);
        assertThat(refused).isEqualTo(7);
    }
}
