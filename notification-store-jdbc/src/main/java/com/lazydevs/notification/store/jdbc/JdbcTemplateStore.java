package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.template.TemplateApprovalStatus;
import com.lazydevs.notification.api.template.TemplateEvent;
import com.lazydevs.notification.api.template.TemplateEventAction;
import com.lazydevs.notification.api.template.TemplateFilter;
import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.api.template.TemplateStore;
import com.lazydevs.notification.api.template.TemplateStoreChecks;
import com.lazydevs.notification.api.template.TemplateStoreException;
import com.lazydevs.notification.api.template.TemplateSummary;
import com.lazydevs.notification.api.template.TemplateVersion;
import com.lazydevs.notification.api.template.TemplateVersionStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * PostgreSQL-backed {@link TemplateStore} (DD-38) over plain SQL.
 *
 * <p>Each key's draft, versions and events live in the {@code template_version} and
 * {@code template_event} tables of {@link JdbcStoreSchema}. Every write runs in one
 * transaction that first takes a transaction-scoped advisory lock on the key, so writes to
 * one key are serialised across replicas, and appends its event in the same transaction.
 * Partial unique indexes keep at most one draft and one published version per key.
 *
 * <p>Keys carry their tenant ({@code NULL} for the default layer); the store does not read
 * {@code TenantContext}. Under row-level security the session must be allowed to see and
 * write the key's tenant; the cross-tenant listing can go through
 * {@code notification.store.jdbc.template.summary-function}.
 *
 * <p>Every statement is built once, in the constructor, from validated table and function
 * names. The library never runs DDL; see {@link JdbcStoreSchema}.
 */
public class JdbcTemplateStore implements TemplateStore {

    private static final Pattern FUNCTION = Pattern.compile("[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)?");

    private static final String KEY_WHERE = " WHERE tenant_key = :tenantKey AND channel = :channel"
            + " AND template_id = :templateId AND locale = :locale";

    private static final String SUMMARY_COLUMNS = "tenant_id, channel, template_id, locale, version, status,"
            + " content_type, revision, created_by, created_at, updated_by, updated_at, published_by, published_at,"
            + " note, provider_status, provider_ref, provider_reason";

    private static final String COLUMNS = SUMMARY_COLUMNS + ", content, sample_model";
    private static final String RETURNING = " RETURNING " + COLUMNS;
    private static final String NOT_DRAFT = " AND status <> 'DRAFT'";
    private static final String ONLY_DRAFT = " AND status = 'DRAFT'";
    private static final String ONLY_PUBLISHED = " AND status = 'PUBLISHED'";

    // Named parameters; several double as column names
    private static final String TENANT = "tenant";
    private static final String CHANNEL = "channel";
    private static final String TEMPLATE_ID = "templateId";
    private static final String CONTENT_TYPE = "contentType";
    private static final String CONTENT = "content";
    private static final String SAMPLE_MODEL = "sampleModel";
    private static final String ACTOR = "actor";
    private static final String NOTE = "note";
    private static final String REVISION = "revision";
    private static final String VERSION = "version";
    private static final String STATUS = "status";
    private static final String PROVIDER_STATUS = "providerStatus";
    private static final String PROVIDER_REF = "providerRef";
    private static final String PROVIDER_REASON = "providerReason";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final String lockPrefix;
    private final int maxContentBytes;

    private final String byStatusSql;
    private final String versionsSql;
    private final String versionSql;
    private final String summariesSql;
    private final String eventsSql;
    private final String insertDraftSql;
    private final String updateDraftSql;
    private final String nextVersionSql;
    private final String supersedeSql;
    private final String publishSql;
    private final String restoreSql;
    private final String discardSql;
    private final String archiveSql;
    private final String approvalSql;
    private final String eventSql;

    /**
     * @param summaryFunction optional {@code SECURITY DEFINER} function for {@link #summaries};
     *                        {@code null} or blank reads the table
     */
    public JdbcTemplateStore(DataSource dataSource, JdbcStoreTables tables, int maxContentBytes,
                             String summaryFunction) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.maxContentBytes = TemplateStoreChecks.requirePositiveLimit(maxContentBytes);
        String function = function(summaryFunction, "notification.store.jdbc.template.summary-function");
        String v = tables.templateVersion();
        String e = tables.templateEvent();
        this.lockPrefix = v + ":";

        String select = select(COLUMNS, v) + KEY_WHERE;
        this.byStatusSql = select + " AND status = :status";
        this.versionsSql = select + NOT_DRAFT + " ORDER BY version DESC";
        this.versionSql = select + NOT_DRAFT + " AND version = :version";
        this.summariesSql = function != null
                ? select(SUMMARY_COLUMNS, function + "(:channel, :templateId)")
                + " ORDER BY tenant_id NULLS FIRST, channel, template_id, locale"
                : select(SUMMARY_COLUMNS, v) + " WHERE status IN ('DRAFT', 'PUBLISHED')"
                + " AND (CAST(:channel AS VARCHAR) IS NULL OR channel = :channel)"
                + " AND (CAST(:templateId AS VARCHAR) IS NULL OR template_id = :templateId)"
                + " ORDER BY tenant_key, channel, template_id, locale";
        this.eventsSql = select("id, tenant_id, channel, template_id, locale, action, version, actor,"
                + " occurred_at, note", e) + KEY_WHERE + " ORDER BY id DESC LIMIT :limit";

        this.insertDraftSql = insert(v, "tenant_id, channel, template_id, locale, version, status, content_type,"
                + " content, sample_model, revision, created_by, created_at, updated_by, updated_at, note",
                ":tenant, :channel, :templateId, :locale, 0, 'DRAFT', :contentType, :content, :sampleModel,"
                        + " :revision, :actor, now(), :actor, now(), :note") + RETURNING;
        this.updateDraftSql = update(v, "content_type = :contentType, content = :content,"
                + " sample_model = :sampleModel, revision = :revision, updated_by = :actor, updated_at = now(),"
                + " note = :note") + ONLY_DRAFT + RETURNING;
        this.nextVersionSql = select("COALESCE(MAX(version), 0) + 1", v) + KEY_WHERE;
        this.supersedeSql = update(v, "status = 'SUPERSEDED'") + ONLY_PUBLISHED;
        this.publishSql = update(v, "status = 'PUBLISHED', version = :version, published_by = :actor,"
                + " published_at = now(), note = :note") + ONLY_DRAFT + RETURNING;
        this.restoreSql = insert(v, "tenant_id, channel, template_id, locale, version, status, content_type,"
                + " content, sample_model, revision, created_by, created_at, updated_by, updated_at, published_by,"
                + " published_at, note, provider_status, provider_ref, provider_reason",
                ":tenant, :channel, :templateId, :locale, :version, 'PUBLISHED', :contentType, :content,"
                        + " :sampleModel, :revision, :actor, now(), :actor, now(), :actor, now(), :note,"
                        + " :providerStatus, :providerRef, :providerReason") + RETURNING;
        this.discardSql = "DELETE FROM " + v + KEY_WHERE + ONLY_DRAFT;
        this.archiveSql = update(v, "status = 'ARCHIVED'") + ONLY_PUBLISHED + " RETURNING version";
        this.approvalSql = update(v, "provider_status = :providerStatus, provider_ref = :providerRef,"
                + " provider_reason = :providerReason") + NOT_DRAFT + " AND version = :version"
                + RETURNING;
        this.eventSql = insert(e, "tenant_id, channel, template_id, locale, action, version, actor, occurred_at,"
                + " note", ":tenant, :channel, :templateId, :locale, :action, :version, :actor, now(), :note")
                + " RETURNING id";
    }

    private static String select(String columns, String from) {
        return "SELECT " + columns + " FROM " + from;
    }

    private static String insert(String table, String columns, String values) {
        return "INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")";
    }

    /** {@code UPDATE table SET assignments} plus the key condition. */
    private static String update(String table, String assignments) {
        return "UPDATE " + table + " SET " + assignments + KEY_WHERE;
    }

    /**
     * A validated, optionally schema-qualified function name, or {@code null} when blank.
     *
     * @throws IllegalArgumentException when the name is not a plain SQL identifier
     */
    static String function(String name, String property) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        if (!FUNCTION.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(property + " must be a plain, optionally schema-qualified,"
                    + " SQL identifier, got '" + trimmed + "'");
        }
        return trimmed;
    }

    // ---------------------------------------------------------------- reads

    @Override
    public Optional<TemplateVersion> findPublished(TemplateKey key) {
        return byStatus(key, TemplateVersionStatus.PUBLISHED);
    }

    @Override
    public Optional<TemplateVersion> findDraft(TemplateKey key) {
        return byStatus(key, TemplateVersionStatus.DRAFT);
    }

    private Optional<TemplateVersion> byStatus(TemplateKey key, TemplateVersionStatus status) {
        return keyed(byStatusSql, key)
                .param(STATUS, status.name())
                .query((rs, n) -> map(rs, true))
                .optional();
    }

    @Override
    public List<TemplateVersion> versions(TemplateKey key) {
        return keyed(versionsSql, key)
                .query((rs, n) -> map(rs, true))
                .list();
    }

    @Override
    public List<TemplateSummary> summaries(TemplateFilter filter) {
        List<TemplateVersion> rows = jdbc.sql(summariesSql)
                .param(CHANNEL, filter.channel(), Types.VARCHAR)
                .param(TEMPLATE_ID, filter.templateId(), Types.VARCHAR)
                .query((rs, n) -> map(rs, false))
                .list();
        Map<TemplateKey, TemplateVersion[]> byKey = new LinkedHashMap<>();
        for (TemplateVersion row : rows) {
            if (filter.matches(row.key())) {
                TemplateVersion[] pair = byKey.computeIfAbsent(row.key(), k -> new TemplateVersion[2]);
                pair[row.status() == TemplateVersionStatus.PUBLISHED ? 0 : 1] = row;
            }
        }
        List<TemplateSummary> result = new ArrayList<>(byKey.size());
        byKey.forEach((key, pair) -> result.add(new TemplateSummary(key, pair[0], pair[1])));
        return result;
    }

    @Override
    public List<TemplateEvent> events(TemplateKey key, int limit) {
        return keyed(eventsSql, key)
                .param("limit", Math.max(1, limit))
                .query(JdbcTemplateStore::mapEvent)
                .list();
    }

    // ---------------------------------------------------------------- writes

    @Override
    public TemplateVersion saveDraft(TemplateKey key, String contentType, String content, String sampleModel,
                                     long expectedRevision, String actor, String note) {
        TemplateStoreChecks.requireActor(key, actor);
        TemplateStoreChecks.requireDraft(key, contentType, content, sampleModel, maxContentBytes);
        return write(key, () -> {
            Optional<TemplateVersion> current = byStatus(key, TemplateVersionStatus.DRAFT);
            long currentRevision = current.map(TemplateVersion::revision).orElse(0L);
            if (expectedRevision != currentRevision) {
                throw TemplateStoreException.revisionConflict(key, expectedRevision, currentRevision);
            }
            // The save's event id is the new revision: increasing, and never reused for the key
            long revision = event(key,
                    current.isEmpty() ? TemplateEventAction.CREATED_DRAFT : TemplateEventAction.UPDATED_DRAFT,
                    null, actor, note);
            return keyed(current.isEmpty() ? insertDraftSql : updateDraftSql, key)
                    .param(TENANT, key.tenantId(), Types.VARCHAR)
                    .param(REVISION, revision)
                    .param(CONTENT_TYPE, contentType)
                    .param(CONTENT, content)
                    .param(SAMPLE_MODEL, sampleModel, Types.VARCHAR)
                    .param(ACTOR, actor)
                    .param(NOTE, note, Types.VARCHAR)
                    .query((rs, n) -> map(rs, true))
                    .single();
        });
    }

    @Override
    public TemplateVersion publish(TemplateKey key, long draftRevision, String actor, String note) {
        TemplateStoreChecks.requireActor(key, actor);
        return write(key, () -> {
            TemplateVersion draft = byStatus(key, TemplateVersionStatus.DRAFT).orElseThrow(() -> noDraft(key));
            if (draft.revision() != draftRevision) {
                throw TemplateStoreException.revisionConflict(key, draftRevision, draft.revision());
            }
            int version = nextVersion(key);
            supersede(key);
            TemplateVersion published = keyed(publishSql, key)
                    .param(VERSION, version)
                    .param(ACTOR, actor)
                    .param(NOTE, note, Types.VARCHAR)
                    .query((rs, n) -> map(rs, true))
                    .single();
            event(key, TemplateEventAction.PUBLISHED, version, actor, note);
            return published;
        });
    }

    @Override
    public TemplateVersion restore(TemplateKey key, int version, String actor, String note) {
        TemplateStoreChecks.requireActor(key, actor);
        return write(key, () -> {
            TemplateVersion source = keyed(versionSql, key)
                    .param(VERSION, version)
                    .query((rs, n) -> map(rs, true))
                    .optional()
                    .orElseThrow(() -> unknownVersion(key, version));
            int next = nextVersion(key);
            supersede(key);
            TemplateVersion restored = keyed(restoreSql, key)
                    .param(TENANT, key.tenantId(), Types.VARCHAR)
                    .param(VERSION, next)
                    .param(CONTENT_TYPE, source.contentType())
                    .param(CONTENT, source.content())
                    .param(SAMPLE_MODEL, source.sampleModel(), Types.VARCHAR)
                    .param(REVISION, source.revision())
                    .param(ACTOR, actor)
                    .param(NOTE, note, Types.VARCHAR)
                    .param(PROVIDER_STATUS, name(source.approvalStatus()), Types.VARCHAR)
                    .param(PROVIDER_REF, source.providerRef(), Types.VARCHAR)
                    .param(PROVIDER_REASON, source.providerReason(), Types.VARCHAR)
                    .query((rs, n) -> map(rs, true))
                    .single();
            event(key, TemplateEventAction.RESTORED, next, actor, note);
            return restored;
        });
    }

    @Override
    public void discardDraft(TemplateKey key, String actor, String note) {
        TemplateStoreChecks.requireActor(key, actor);
        write(key, () -> {
            if (keyed(discardSql, key).update() == 0) {
                throw noDraft(key);
            }
            event(key, TemplateEventAction.DISCARDED, null, actor, note);
            return null;
        });
    }

    @Override
    public void archive(TemplateKey key, String actor, String note) {
        TemplateStoreChecks.requireActor(key, actor);
        write(key, () -> {
            Integer version = keyed(archiveSql, key)
                    .query(Integer.class)
                    .optional()
                    .orElseThrow(() -> new TemplateStoreException(TemplateStoreException.Reason.NO_OVERRIDE, key,
                            subject(key) + " has no published override"));
            event(key, TemplateEventAction.RESET, version, actor, note);
            return null;
        });
    }

    @Override
    public TemplateVersion updateApproval(TemplateKey key, int version, TemplateApprovalStatus status,
                                          String providerRef, String providerReason, String actor) {
        TemplateStoreChecks.requireActor(key, actor);
        return write(key, () -> {
            TemplateVersion updated = keyed(approvalSql, key)
                    .param(PROVIDER_STATUS, name(status), Types.VARCHAR)
                    .param(PROVIDER_REF, providerRef, Types.VARCHAR)
                    .param(PROVIDER_REASON, providerReason, Types.VARCHAR)
                    .param(VERSION, version)
                    .query((rs, n) -> map(rs, true))
                    .optional()
                    .orElseThrow(() -> unknownVersion(key, version));
            event(key, TemplateEventAction.PROVIDER_STATUS, version, actor, name(status));
            return updated;
        });
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Run {@code work} in a transaction that holds the key's advisory lock, so concurrent writes
     * to one key, from any replica, run one after the other.
     */
    private <T> T write(TemplateKey key, Supplier<T> work) {
        return transactions.execute(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lock, 0))")
                    .param("lock", lockPrefix + key.name() + ":" + key.locale())
                    .query((rs, n) -> null)
                    .list();
            return work.get();
        });
    }

    private int nextVersion(TemplateKey key) {
        Integer next = keyed(nextVersionSql, key).query(Integer.class).single();
        return next == null ? 1 : next;
    }

    private void supersede(TemplateKey key) {
        keyed(supersedeSql, key).update();
    }

    /** Append an event; returns its id. */
    private long event(TemplateKey key, TemplateEventAction action, Integer version, String actor, String note) {
        Long id = keyed(eventSql, key)
                .param(TENANT, key.tenantId(), Types.VARCHAR)
                .param("action", action.name())
                .param(VERSION, version, Types.INTEGER)
                .param(ACTOR, actor)
                .param(NOTE, note, Types.VARCHAR)
                .query(Long.class)
                .single();
        return id == null ? 0 : id;
    }

    private JdbcClient.StatementSpec keyed(String sql, TemplateKey key) {
        return jdbc.sql(sql)
                .param("tenantKey", key.tenantId() == null ? "" : key.tenantId())
                .param(CHANNEL, key.channel())
                .param(TEMPLATE_ID, key.templateId())
                .param("locale", key.locale());
    }

    private static String name(TemplateApprovalStatus status) {
        return status == null ? null : status.name();
    }

    private static TemplateKey keyOf(ResultSet rs) throws SQLException {
        return new TemplateKey(rs.getString("tenant_id"), rs.getString(CHANNEL), rs.getString("template_id"),
                rs.getString("locale"));
    }

    private static TemplateVersion map(ResultSet rs, boolean withContent) throws SQLException {
        return TemplateVersion.builder()
                .key(keyOf(rs))
                .version(rs.getInt(VERSION))
                .status(TemplateVersionStatus.valueOf(rs.getString(STATUS)))
                .contentType(rs.getString("content_type"))
                .content(withContent ? rs.getString(CONTENT) : null)
                .sampleModel(withContent ? rs.getString("sample_model") : null)
                .revision(rs.getLong(REVISION))
                .createdBy(rs.getString("created_by"))
                .createdAt(JdbcStoreSupport.instant(rs, "created_at"))
                .updatedBy(rs.getString("updated_by"))
                .updatedAt(JdbcStoreSupport.instant(rs, "updated_at"))
                .publishedBy(rs.getString("published_by"))
                .publishedAt(JdbcStoreSupport.instant(rs, "published_at"))
                .note(rs.getString(NOTE))
                .approvalStatus(JdbcStoreSupport.enumOrDefault(TemplateApprovalStatus.class,
                        rs.getString("provider_status"), null))
                .providerRef(rs.getString("provider_ref"))
                .providerReason(rs.getString("provider_reason"))
                .build();
    }

    private static TemplateEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        int version = rs.getInt(VERSION);
        Integer nullableVersion = rs.wasNull() ? null : version;
        return new TemplateEvent(rs.getLong("id"), keyOf(rs),
                JdbcStoreSupport.enumOrDefault(TemplateEventAction.class, rs.getString("action"), null),
                nullableVersion, rs.getString(ACTOR), JdbcStoreSupport.instant(rs, "occurred_at"),
                rs.getString(NOTE));
    }

    private static String subject(TemplateKey key) {
        return "Template " + key.name();
    }

    private static TemplateStoreException noDraft(TemplateKey key) {
        return new TemplateStoreException(TemplateStoreException.Reason.NO_DRAFT, key,
                subject(key) + " has no draft");
    }

    private static TemplateStoreException unknownVersion(TemplateKey key, int version) {
        return new TemplateStoreException(TemplateStoreException.Reason.UNKNOWN_VERSION, key,
                subject(key) + " has no version " + version);
    }
}
