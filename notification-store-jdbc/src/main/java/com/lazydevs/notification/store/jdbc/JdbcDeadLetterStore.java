package com.lazydevs.notification.store.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.lazydevs.notification.store.jdbc.JdbcStoreSupport.nowPlusMillis;

/**
 * PostgreSQL-backed {@link DeadLetterStore} (DD-13) over plain SQL.
 *
 * <p>One row per {@code (tenant, requestId)}; a duplicate {@link #add} is
 * ignored unless the existing row has expired. The request and response
 * are JSON text columns; tenant, request id, provider, status, failure
 * type, attempts and the timestamps are real columns.
 *
 * <p>The row's {@code tenant_id} is the entry's request tenant when set,
 * else the current {@code TenantContext} tenant, else {@code NULL}. That
 * matches the {@code (tenantId, requestId)} key the replay endpoint looks
 * entries up by.
 *
 * <p>{@link #claim} leases rows with one
 * {@code UPDATE ... RETURNING} over a {@code FOR UPDATE SKIP LOCKED}
 * selection, so concurrent claimers on any number of replicas receive
 * disjoint rows; the targeted {@link #claim(String, String, Duration)}
 * leases a single row the same way. {@link #remove} acknowledges, {@link #release} gives the
 * lease back. Rows past {@code dead-letter-retention} are invisible to
 * every read and are deleted by {@link #purgeExpired(int)}.
 */
@Slf4j
public class JdbcDeadLetterStore implements DeadLetterStore, JdbcPurgeableStore {

    private static final String SELECT_COLUMNS =
            "id, tenant_id, request_id, failed_at, request, response, attempts, failure_type, created_at";

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final long retentionMillis;
    private final int maxEntries;

    private final String addSql;
    private final String snapshotSql;
    private final String countSql;
    private final String findSql;
    private final String removeSql;
    private final String claimSql;
    private final String claimOneSql;
    private final String releaseSql;
    private final String purgeSql;

    public JdbcDeadLetterStore(JdbcClient jdbc, ObjectMapper json, JdbcStoreTables tables,
                               Duration retention, int maxEntries) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.retentionMillis = JdbcStoreSupport.millis(
                JdbcStoreSupport.requirePositive(retention, "notification.store.jdbc.dead-letter-retention"));
        this.maxEntries = JdbcStoreSupport.requirePositive(maxEntries, "notification.dead-letter.max-entries");
        String t = tables.deadLetter();
        String live = "expires_at > now()";
        String keyMatch = "tenant_key = COALESCE(:tenant, '') AND request_id = :requestId";

        this.addSql = "INSERT INTO " + t + " AS t (tenant_id, request_id, provider_name, status, failure_type,"
                + " attempts, failed_at, request, response, claimed_until, created_at, expires_at)"
                + " VALUES (:tenant, :requestId, :provider, :status, :failureType, :attempts, :failedAt,"
                + " :request, :response, NULL, now(), " + nowPlusMillis("retention") + ")"
                + " ON CONFLICT (tenant_key, request_id) DO UPDATE SET"
                + " tenant_id = EXCLUDED.tenant_id, provider_name = EXCLUDED.provider_name,"
                + " status = EXCLUDED.status, failure_type = EXCLUDED.failure_type,"
                + " attempts = EXCLUDED.attempts, failed_at = EXCLUDED.failed_at,"
                + " request = EXCLUDED.request, response = EXCLUDED.response, claimed_until = NULL,"
                + " created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at"
                + " WHERE t.expires_at <= now()";
        this.snapshotSql = "SELECT " + SELECT_COLUMNS + " FROM " + t + " WHERE " + live
                + " ORDER BY created_at DESC, id DESC LIMIT :limit";
        this.countSql = "SELECT COUNT(*) FROM " + t + " WHERE " + live;
        this.findSql = "SELECT " + SELECT_COLUMNS + " FROM " + t + " WHERE " + keyMatch + " AND " + live;
        this.removeSql = "DELETE FROM " + t + " WHERE " + keyMatch;
        // The CTE is MATERIALIZED so the locking selection runs exactly
        // once; the outer UPDATE touches only the rows it locked.
        String unclaimed = live + " AND (claimed_until IS NULL OR claimed_until < now())";
        String leaseClaimable = " UPDATE " + t + " AS d SET claimed_until = " + nowPlusMillis("lease")
                + " WHERE d.id IN (SELECT id FROM claimable)"
                + " RETURNING d.id, d.tenant_id, d.request_id, d.failed_at, d.request, d.response,"
                + " d.attempts, d.failure_type, d.created_at";
        this.claimSql = "WITH claimable AS MATERIALIZED ("
                + "SELECT id FROM " + t
                + " WHERE tenant_key = COALESCE(:tenant, '') AND " + unclaimed
                + " ORDER BY created_at, id LIMIT :limit FOR UPDATE SKIP LOCKED)"
                + leaseClaimable;
        this.claimOneSql = "WITH claimable AS MATERIALIZED ("
                + "SELECT id FROM " + t
                + " WHERE " + keyMatch + " AND " + unclaimed
                + " FOR UPDATE SKIP LOCKED)"
                + leaseClaimable;
        this.releaseSql = "UPDATE " + t + " SET claimed_until = NULL WHERE " + keyMatch;
        this.purgeSql = "DELETE FROM " + t + " WHERE id IN (SELECT id FROM " + t
                + " WHERE expires_at <= now() LIMIT :limit FOR UPDATE SKIP LOCKED)";
    }

    @Override
    public void add(DeadLetterEntry entry) {
        // SPI contract: never throw. A DLQ failure must not turn an
        // already-failed send into a second, caller-visible failure.
        try {
            NotificationRequest request = entry.request();
            NotificationResponse response = entry.response();
            String requestId = JdbcStoreSupport.hasText(request.getRequestId())
                    ? request.getRequestId() : response.requestId();
            if (!JdbcStoreSupport.hasText(requestId)) {
                log.warn("Dropping dead-letter entry without a request id");
                return;
            }
            jdbc.sql(addSql)
                    .param("tenant", JdbcStoreSupport.tenantFor(request.getTenantId()), Types.VARCHAR)
                    .param("requestId", requestId, Types.VARCHAR)
                    .param("provider", response.provider(), Types.VARCHAR)
                    .param("status", response.status().name(), Types.VARCHAR)
                    .param("failureType", entry.failureType().name(), Types.VARCHAR)
                    .param("attempts", entry.attempts())
                    .param("failedAt", JdbcStoreSupport.timestamp(entry.timestamp()))
                    .param("request", json.writeValueAsString(request), Types.VARCHAR)
                    .param("response", json.writeValueAsString(response), Types.VARCHAR)
                    .param("retention", retentionMillis)
                    .update();
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise dead-letter entry; swallowing: {}", e.toString());
        } catch (RuntimeException e) {
            log.warn("Failed to record dead-letter entry; swallowing: {}", e.toString());
        }
    }

    /** Most recent first, at most {@code notification.dead-letter.max-entries} rows. */
    @Override
    public Optional<List<DeadLetterEntry>> snapshot() {
        return Optional.of(jdbc.sql(snapshotSql)
                .param("limit", maxEntries)
                .query(this::mapEntry)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .toList());
    }

    /**
     * Live rows across all tenants, the same global count the in-memory and
     * Redis stores report (the health indicator and metrics gauge rely on
     * it). {@code TenantContext} does not narrow it; row-level security, if
     * the host enables it, does.
     */
    @Override
    public int size() {
        Long count = jdbc.sql(countSql).query(Long.class).single();
        return (int) Math.min(Integer.MAX_VALUE, count == null ? 0L : count);
    }

    @Override
    public Optional<DeadLetterEntry> findByRequestId(String tenantId, String requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        return keyParams(jdbc.sql(findSql), tenantId, requestId)
                .query(this::mapEntry)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    /** Deletes the row; also the acknowledgement for a claimed row. Never throws. */
    @Override
    public boolean remove(String tenantId, String requestId) {
        if (requestId == null) {
            return false;
        }
        try {
            return keyParams(jdbc.sql(removeSql), tenantId, requestId).update() > 0;
        } catch (RuntimeException e) {
            log.warn("Failed to remove dead-letter entry; swallowing: {}", e.toString());
            return false;
        }
    }

    /**
     * Leases up to {@code limit} unclaimed live rows of {@code tenantId},
     * oldest first, until {@code now() + lease}, in one statement.
     */
    @Override
    public List<DeadLetterEntry> claim(String tenantId, int limit, Duration lease) {
        if (limit <= 0) {
            return List.of();
        }
        long leaseMillis = JdbcStoreSupport.millis(JdbcStoreSupport.requirePositive(lease, "lease"));
        return jdbc.sql(claimSql)
                .param("tenant", tenantId, Types.VARCHAR)
                .param("limit", limit)
                .param("lease", leaseMillis)
                .query(this::mapClaimed)
                .list()
                .stream()
                // RETURNING has no defined order; restore oldest first.
                .sorted(Comparator.comparing(ClaimedRow::createdAt).thenComparingLong(ClaimedRow::id))
                .map(ClaimedRow::entry)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Leases the one live, unclaimed row of {@code (tenantId, requestId)}
     * until {@code now() + lease}. Empty when the row is missing, expired,
     * leased by someone else, or locked by a concurrent claim.
     */
    @Override
    public Optional<DeadLetterEntry> claim(String tenantId, String requestId, Duration lease) {
        if (requestId == null) {
            return Optional.empty();
        }
        long leaseMillis = JdbcStoreSupport.millis(JdbcStoreSupport.requirePositive(lease, "lease"));
        return keyParams(jdbc.sql(claimOneSql), tenantId, requestId)
                .param("lease", leaseMillis)
                .query(this::mapEntry)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    /** Clears the lease so the row is claimable again immediately. Never throws. */
    @Override
    public void release(String tenantId, String requestId) {
        if (requestId == null) {
            return;
        }
        try {
            keyParams(jdbc.sql(releaseSql), tenantId, requestId).update();
        } catch (RuntimeException e) {
            log.warn("Failed to release dead-letter entry; swallowing: {}", e.toString());
        }
    }

    @Override
    public int purgeExpired(int batchSize) {
        return jdbc.sql(purgeSql)
                .param("limit", JdbcStoreSupport.requirePositive(batchSize, "batchSize"))
                .update();
    }

    private static JdbcClient.StatementSpec keyParams(JdbcClient.StatementSpec spec, String tenantId, String requestId) {
        return spec
                .param("tenant", tenantId, Types.VARCHAR)
                .param("requestId", requestId, Types.VARCHAR);
    }

    private ClaimedRow mapClaimed(ResultSet rs, int rowNum) throws SQLException {
        return new ClaimedRow(rs.getLong("id"), JdbcStoreSupport.instant(rs, "created_at"), mapEntry(rs, rowNum));
    }

    private DeadLetterEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new DeadLetterEntry(
                    JdbcStoreSupport.instant(rs, "failed_at"),
                    json.readValue(rs.getString("request"), NotificationRequest.class),
                    json.readValue(rs.getString("response"), NotificationResponse.class),
                    rs.getInt("attempts"),
                    JdbcStoreSupport.enumOrDefault(FailureType.class, rs.getString("failure_type"),
                            FailureType.UNKNOWN));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("Skipping malformed dead-letter row id={}: {}", rs.getLong("id"), e.getMessage());
            return null;
        }
    }

    private record ClaimedRow(long id, java.time.Instant createdAt, DeadLetterEntry entry) {
    }
}
