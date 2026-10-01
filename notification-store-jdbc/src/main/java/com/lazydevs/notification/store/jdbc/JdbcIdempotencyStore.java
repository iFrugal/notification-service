package com.lazydevs.notification.store.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.NotificationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import static com.lazydevs.notification.store.jdbc.JdbcStoreSupport.nowPlusMillis;

/**
 * PostgreSQL-backed {@link IdempotencyStore} (DD-10) over plain SQL.
 *
 * <p>One row per {@code (tenant, caller, key)}. Uniqueness is enforced by
 * the primary key over the generated {@code tenant_key} and
 * {@code caller_key} columns ({@code COALESCE(x, '')}) plus
 * {@code idem_key}, so the same key in two tenants, or from two callers,
 * never collides.
 *
 * <p>{@link #markInProgress} is a single
 * {@code INSERT ... ON CONFLICT ... DO UPDATE ... WHERE <existing row expired>}
 * statement: the row count is 1 when this caller created the row or took
 * over an expired one, and 0 when a live row already exists. PostgreSQL
 * serialises concurrent upserts on the conflicting key, so exactly one of
 * any number of racing callers (on any number of replicas) wins.
 *
 * <p>Expiry uses database time ({@code now()}). Reads ignore expired
 * rows; {@link #purgeExpired(int)} deletes them.
 *
 * <p>The row's {@code tenant_id} is {@link IdempotencyKey#tenantId()},
 * which is the SPI's dedup scope and is always set.
 */
@Slf4j
public class JdbcIdempotencyStore implements IdempotencyStore, JdbcPurgeableStore {

    private static final String COLUMNS =
            "tenant_id, caller_id, idem_key, notification_id, status, response, created_at, recorded_at, expires_at";

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final long ttlMillis;
    private final int purgeBatchSize;

    private final String findSql;
    private final String markInProgressSql;
    private final String markCompleteSql;
    private final String purgeSql;

    public JdbcIdempotencyStore(JdbcClient jdbc, ObjectMapper json, JdbcStoreTables tables,
                                Duration ttl, int purgeBatchSize) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.ttlMillis = JdbcStoreSupport.millis(JdbcStoreSupport.requirePositive(ttl, "notification.idempotency.ttl"));
        this.purgeBatchSize = JdbcStoreSupport.requirePositive(purgeBatchSize, "notification.store.jdbc.purge.batch-size");
        String t = tables.idempotency();
        String keyMatch = "tenant_key = COALESCE(:tenant, '') AND caller_key = COALESCE(:caller, '') AND idem_key = :key";

        this.findSql = "SELECT notification_id, status, response, recorded_at FROM " + t
                + " WHERE " + keyMatch + " AND expires_at > now()";

        this.markInProgressSql = "INSERT INTO " + t + " AS t (" + COLUMNS + ")"
                + " VALUES (:tenant, :caller, :key, :notificationId, 'IN_PROGRESS', NULL, now(), now(), "
                + nowPlusMillis("ttl") + ")"
                + " ON CONFLICT (tenant_key, caller_key, idem_key) DO UPDATE SET"
                + " tenant_id = EXCLUDED.tenant_id, caller_id = EXCLUDED.caller_id,"
                + " notification_id = EXCLUDED.notification_id, status = EXCLUDED.status, response = NULL,"
                + " created_at = EXCLUDED.created_at, recorded_at = EXCLUDED.recorded_at,"
                + " expires_at = EXCLUDED.expires_at"
                + " WHERE t.expires_at <= now()";

        // Upsert so a completion whose IN_PROGRESS row was already purged is
        // still cached (same as the Redis store's unconditional SET). The
        // original notification id is kept unless the old row had expired.
        this.markCompleteSql = "INSERT INTO " + t + " AS t (" + COLUMNS + ")"
                + " VALUES (:tenant, :caller, :key, :notificationId, 'COMPLETE', :response, now(), now(), "
                + nowPlusMillis("ttl") + ")"
                + " ON CONFLICT (tenant_key, caller_key, idem_key) DO UPDATE SET"
                + " notification_id = CASE WHEN t.expires_at <= now()"
                + " THEN EXCLUDED.notification_id ELSE t.notification_id END,"
                + " created_at = CASE WHEN t.expires_at <= now() THEN EXCLUDED.created_at ELSE t.created_at END,"
                + " status = EXCLUDED.status, response = EXCLUDED.response,"
                + " recorded_at = EXCLUDED.recorded_at, expires_at = EXCLUDED.expires_at";

        this.purgeSql = "DELETE FROM " + t + " WHERE (tenant_key, caller_key, idem_key) IN ("
                + "SELECT tenant_key, caller_key, idem_key FROM " + t
                + " WHERE expires_at <= now() LIMIT :limit FOR UPDATE SKIP LOCKED)";
    }

    @Override
    public Optional<IdempotencyRecord> findExisting(IdempotencyKey key) {
        return keyParams(jdbc.sql(findSql), key)
                .query(this::mapRecord)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    @Override
    public boolean markInProgress(IdempotencyKey key, String notificationId) {
        int rows = keyParams(jdbc.sql(markInProgressSql), key)
                .param("notificationId", Objects.requireNonNull(notificationId, "notificationId"), Types.VARCHAR)
                .param("ttl", ttlMillis)
                .update();
        return rows == 1;
    }

    @Override
    public void markComplete(IdempotencyKey key, NotificationResponse response) {
        String body;
        try {
            body = json.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            // A programming error, not a runtime condition: same as the Redis store.
            throw new IllegalStateException("Failed to serialise NotificationResponse", e);
        }
        keyParams(jdbc.sql(markCompleteSql), key)
                .param("notificationId", Objects.requireNonNullElse(response.requestId(), ""), Types.VARCHAR)
                .param("response", body, Types.VARCHAR)
                .param("ttl", ttlMillis)
                .update();
    }

    /** Deletes every expired row, one batch of {@code purge.batch-size} at a time. */
    @Override
    public void evictExpired() {
        int deleted;
        do {
            deleted = purgeExpired(purgeBatchSize);
        } while (deleted >= purgeBatchSize);
    }

    @Override
    public int purgeExpired(int batchSize) {
        return jdbc.sql(purgeSql)
                .param("limit", JdbcStoreSupport.requirePositive(batchSize, "batchSize"))
                .update();
    }

    private static JdbcClient.StatementSpec keyParams(JdbcClient.StatementSpec spec, IdempotencyKey key) {
        return spec
                .param("tenant", key.tenantId(), Types.VARCHAR)
                .param("caller", key.callerId(), Types.VARCHAR)
                .param("key", key.idempotencyKey(), Types.VARCHAR);
    }

    private IdempotencyRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        String body = rs.getString("response");
        NotificationResponse response = null;
        if (body != null) {
            try {
                response = json.readValue(body, NotificationResponse.class);
            } catch (JsonProcessingException e) {
                // Same as the Redis store: a malformed row reads as absent
                // rather than breaking the request flow.
                log.warn("Malformed idempotency response JSON; treating the row as absent: {}", e.getMessage());
                return null;
            }
        }
        return new IdempotencyRecord(
                rs.getString("notification_id"),
                IdempotencyStatus.valueOf(rs.getString("status")),
                response,
                JdbcStoreSupport.instant(rs, "recorded_at"));
    }
}
