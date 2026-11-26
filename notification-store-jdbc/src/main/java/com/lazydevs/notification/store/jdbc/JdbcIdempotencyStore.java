package com.lazydevs.notification.store.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.NotificationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
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
 *
 * <p>The {@code fingerprint} column (DD-32, since 1.2.1) is optional: a
 * 1.2.0 table without it keeps working. The store probes for the column
 * once ({@link #detectFingerprintColumn()}, called at startup by the
 * auto-configuration, otherwise on first use); when it is missing it logs
 * one WARN with the {@code ALTER TABLE} statement and stores no
 * fingerprints, so {@code notification.idempotency.reject-key-reuse} cannot
 * compare them.
 */
@Slf4j
public class JdbcIdempotencyStore implements IdempotencyStore, JdbcPurgeableStore {

    /** PostgreSQL SQLSTATE {@code undefined_column}. */
    private static final String UNDEFINED_COLUMN = "42703";

    private static final String WHERE = " WHERE ";
    private static final String FINGERPRINT = "fingerprint";
    private static final String RESPONSE = "response";
    private static final String NOTIFICATION_ID = "notificationId";

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final long ttlMillis;
    private final int purgeBatchSize;
    private final String table;
    private final boolean requireFingerprint;

    private final Statements withFingerprint;
    private final Statements withoutFingerprint;
    /** {@code null} until the fingerprint column has been detected. */
    private volatile Statements statements;
    private final String releaseSql;
    private final String settleSql;
    private final String purgeSql;
    private final String fingerprintProbeSql;

    /**
     * The 1.2.0 constructor: the fingerprint column is detected on first use
     * and its absence never fails.
     */
    public JdbcIdempotencyStore(JdbcClient jdbc, ObjectMapper json, JdbcStoreTables tables,
                                Duration ttl, int purgeBatchSize) {
        this(jdbc, json, tables, ttl, purgeBatchSize, false);
    }

    /**
     * @param requireFingerprint {@code notification.idempotency.reject-key-reuse}:
     *                           {@link #detectFingerprintColumn()} fails when the
     *                           {@code fingerprint} column is missing
     * @since 1.2.1
     */
    public JdbcIdempotencyStore(JdbcClient jdbc, ObjectMapper json, JdbcStoreTables tables,
                                Duration ttl, int purgeBatchSize, boolean requireFingerprint) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.ttlMillis = JdbcStoreSupport.millis(JdbcStoreSupport.requirePositive(ttl, "notification.idempotency.ttl"));
        this.purgeBatchSize = JdbcStoreSupport.requirePositive(purgeBatchSize, "notification.store.jdbc.purge.batch-size");
        this.requireFingerprint = requireFingerprint;
        String t = tables.idempotency();
        this.table = t;
        String keyMatch = "tenant_key = COALESCE(:tenant, '') AND caller_key = COALESCE(:caller, '') AND idem_key = :key";
        this.withFingerprint = Statements.of(t, keyMatch, true);
        this.withoutFingerprint = Statements.of(t, keyMatch, false);

        // Compare-and-delete in one statement: only a COMPLETE row that still
        // belongs to the given notification id goes; a re-claimed
        // (IN_PROGRESS) row or another caller's row is left alone.
        this.releaseSql = "DELETE FROM " + t + WHERE + keyMatch
                + " AND notification_id = :notificationId AND status = 'COMPLETE'";

        // Compare-and-set in one statement: only a live IN_PROGRESS row that
        // still belongs to the given notification id is settled (DD-32).
        this.settleSql = "UPDATE " + t + " SET status = 'COMPLETE', response = :response,"
                + " recorded_at = now(), expires_at = " + nowPlusMillis("ttl")
                + WHERE + keyMatch + " AND notification_id = :notificationId AND status = 'IN_PROGRESS'"
                + " AND expires_at > now()";

        this.purgeSql = "DELETE FROM " + t + " WHERE (tenant_key, caller_key, idem_key) IN ("
                + "SELECT tenant_key, caller_key, idem_key FROM " + t
                + " WHERE expires_at <= now() LIMIT :limit FOR UPDATE SKIP LOCKED)";

        // Built once from the validated table name (JdbcStoreTables), never per call.
        this.fingerprintProbeSql = "SELECT fingerprint FROM " + t + " WHERE 1 = 0";
    }

    /**
     * Probe the table for the {@code fingerprint} column (DD-32) and remember
     * the answer. Missing: one WARN with the {@code ALTER TABLE} statement,
     * and the store keeps no fingerprints. When the probe cannot tell (the
     * database is unreachable, the table does not exist yet) nothing is
     * remembered and the next use probes again.
     *
     * @return whether the column exists; empty when the probe could not tell
     * @throws IllegalStateException when the column is missing and the store
     *                               was built with {@code requireFingerprint}
     * @since 1.2.1
     */
    public Optional<Boolean> detectFingerprintColumn() {
        Optional<Boolean> present = probeFingerprintColumn();
        present.ifPresent(p -> {
            if (!p) {
                String message = missingFingerprintMessage();
                if (requireFingerprint) {
                    throw new IllegalStateException(message);
                }
                log.warn(message);
            }
            statements = p ? withFingerprint : withoutFingerprint;
        });
        return present;
    }

    private Optional<Boolean> probeFingerprintColumn() {
        try {
            jdbc.sql(fingerprintProbeSql).query().listOfRows();
            return Optional.of(Boolean.TRUE);
        } catch (DataAccessException e) {
            if (isUndefinedColumn(e)) {
                return Optional.of(Boolean.FALSE);
            }
            log.debug("Could not probe {} for the fingerprint column; probing again on next use: {}",
                    table, e.toString());
            return Optional.empty();
        }
    }

    private static boolean isUndefinedColumn(Throwable e) {
        for (Throwable cur = e; cur != null; cur = cur.getCause()) {
            if (cur instanceof SQLException sql && UNDEFINED_COLUMN.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private String missingFingerprintMessage() {
        return "Idempotency table " + table + " has no fingerprint column (added in 1.2.1, DD-32);"
                + " idempotency keeps working without request fingerprints, so"
                + " notification.idempotency.reject-key-reuse cannot detect a reused key."
                + " Add the column with: ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS fingerprint VARCHAR(64)";
    }

    /**
     * The statements for the detected schema. Until the column has been
     * detected, the startup probe is retried here; a lazily detected missing
     * column never throws, even with {@code requireFingerprint}, because the
     * send path must keep working: it logs the WARN instead.
     */
    private Statements statements() {
        Statements current = statements;
        if (current != null) {
            return current;
        }
        Optional<Boolean> present = probeFingerprintColumn();
        if (present.isEmpty()) {
            return withFingerprint;
        }
        boolean hasColumn = present.get();
        synchronized (this) {
            if (statements == null) {
                if (!hasColumn) {
                    log.warn(missingFingerprintMessage());
                }
                statements = hasColumn ? withFingerprint : withoutFingerprint;
            }
            return statements;
        }
    }

    @Override
    public Optional<IdempotencyRecord> findExisting(IdempotencyKey key) {
        return keyParams(jdbc.sql(statements().find()), key)
                .query(this::mapRecord)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    @Override
    public boolean markInProgress(IdempotencyKey key, String notificationId) {
        return markInProgress(key, notificationId, null);
    }

    @Override
    public boolean markInProgress(IdempotencyKey key, String notificationId, String fingerprint) {
        int rows = keyParams(jdbc.sql(statements().markInProgress()), key)
                .param(NOTIFICATION_ID, Objects.requireNonNull(notificationId, NOTIFICATION_ID), Types.VARCHAR)
                .param(FINGERPRINT, fingerprint, Types.VARCHAR)
                .param("ttl", ttlMillis)
                .update();
        return rows == 1;
    }

    @Override
    public void markComplete(IdempotencyKey key, NotificationResponse response) {
        keyParams(jdbc.sql(statements().markComplete()), key)
                .param(NOTIFICATION_ID, Objects.requireNonNullElse(response.requestId(), ""), Types.VARCHAR)
                .param(RESPONSE, storedJson(response), Types.VARCHAR)
                .param("ttl", ttlMillis)
                .update();
    }

    @Override
    public boolean settleInProgress(IdempotencyKey key, String notificationId, NotificationResponse response) {
        if (notificationId == null) {
            return false;
        }
        int rows = keyParams(jdbc.sql(settleSql), key)
                .param(NOTIFICATION_ID, notificationId, Types.VARCHAR)
                .param(RESPONSE, storedJson(response), Types.VARCHAR)
                .param("ttl", ttlMillis)
                .update();
        return rows == 1;
    }

    private String storedJson(NotificationResponse response) {
        try {
            return json.writeValueAsString(IdempotencyStore.storedForm(response));
        } catch (JsonProcessingException e) {
            // A programming error, not a runtime condition: same as the Redis store.
            throw new IllegalStateException("Failed to serialise NotificationResponse", e);
        }
    }

    @Override
    public boolean release(IdempotencyKey key, String notificationId) {
        if (notificationId == null) {
            return false;
        }
        int rows = keyParams(jdbc.sql(releaseSql), key)
                .param(NOTIFICATION_ID, notificationId, Types.VARCHAR)
                .update();
        return rows == 1;
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
        String body = rs.getString(RESPONSE);
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
                JdbcStoreSupport.instant(rs, "recorded_at"),
                rs.getString(FINGERPRINT));
    }

    /**
     * The statements that touch the {@code fingerprint} column, in the form
     * with or without it.
     */
    private record Statements(String find, String markInProgress, String markComplete) {

        static Statements of(String t, String keyMatch, boolean fingerprint) {
            String columns = "tenant_id, caller_id, idem_key, notification_id, status, response,"
                    + (fingerprint ? " fingerprint," : "") + " created_at, recorded_at, expires_at";
            String find = "SELECT notification_id, status, response, "
                    + (fingerprint ? "fingerprint" : "NULL AS fingerprint") + ", recorded_at FROM " + t
                    + WHERE + keyMatch + " AND expires_at > now()";

            String markInProgress = "INSERT INTO " + t + " AS t (" + columns + ")"
                    + " VALUES (:tenant, :caller, :key, :notificationId, 'IN_PROGRESS', NULL,"
                    + (fingerprint ? " :fingerprint," : "") + " now(), now(), " + nowPlusMillis("ttl") + ")"
                    + " ON CONFLICT (tenant_key, caller_key, idem_key) DO UPDATE SET"
                    + " tenant_id = EXCLUDED.tenant_id, caller_id = EXCLUDED.caller_id,"
                    + " notification_id = EXCLUDED.notification_id, status = EXCLUDED.status, response = NULL,"
                    + (fingerprint ? " fingerprint = EXCLUDED.fingerprint," : "")
                    + " created_at = EXCLUDED.created_at, recorded_at = EXCLUDED.recorded_at,"
                    + " expires_at = EXCLUDED.expires_at"
                    + " WHERE t.expires_at <= now()";

            // Upsert so a completion whose IN_PROGRESS row was already purged is
            // still cached (same as the Redis store's unconditional SET). The
            // original notification id and fingerprint are kept unless the old row had expired.
            String markComplete = "INSERT INTO " + t + " AS t (" + columns + ")"
                    + " VALUES (:tenant, :caller, :key, :notificationId, 'COMPLETE', :response,"
                    + (fingerprint ? " NULL," : "") + " now(), now(), " + nowPlusMillis("ttl") + ")"
                    + " ON CONFLICT (tenant_key, caller_key, idem_key) DO UPDATE SET"
                    + " notification_id = CASE WHEN t.expires_at <= now()"
                    + " THEN EXCLUDED.notification_id ELSE t.notification_id END,"
                    + (fingerprint ? " fingerprint = CASE WHEN t.expires_at <= now() THEN NULL ELSE t.fingerprint END,"
                            : "")
                    + " created_at = CASE WHEN t.expires_at <= now() THEN EXCLUDED.created_at ELSE t.created_at END,"
                    + " status = EXCLUDED.status, response = EXCLUDED.response,"
                    + " recorded_at = EXCLUDED.recorded_at, expires_at = EXCLUDED.expires_at";
            return new Statements(find, markInProgress, markComplete);
        }
    }
}
