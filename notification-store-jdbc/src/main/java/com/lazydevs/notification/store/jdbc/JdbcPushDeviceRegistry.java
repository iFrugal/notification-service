package com.lazydevs.notification.store.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.push.PushDevice;
import com.lazydevs.notification.api.push.PushDeviceRegistration;
import com.lazydevs.notification.api.push.PushDeviceRegistry;
import com.lazydevs.notification.api.push.PushDeviceRemovalReasons;
import com.lazydevs.notification.api.push.PushPlatform;
import com.lazydevs.notification.api.push.PushTokens;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.lazydevs.notification.store.jdbc.JdbcStoreSupport.nowPlusMillis;
import static com.lazydevs.notification.store.jdbc.JdbcStoreSupport.timestamp;

/**
 * PostgreSQL-backed {@link PushDeviceRegistry} (DD-37) over plain SQL, on the
 * {@code push_device} table of {@link JdbcStoreSchema}.
 *
 * <ul>
 *   <li>Scope: the generated {@code tenant_key} ({@code COALESCE(tenant_id, '')}) of the
 *       {@code TenantContext} tenant, or of the registration's own tenant; the pool (no
 *       tenant) is one scope. Under row-level security the connection's policy narrows
 *       every statement further, exactly as for the other stores.</li>
 *   <li>A device is unique per {@code (tenant_key, user_id, installation_id)} and per
 *       {@code (tenant_key, token_sha256)}, the full SHA-256 of the token, so a token is never
 *       indexed itself.</li>
 *   <li>{@link #register} runs in one transaction that first takes two transaction-scoped
 *       advisory locks, on the scope and user and then on the scope and token, so two
 *       registrations of one user cannot miss each other's eviction and two registrations
 *       of one token cannot both insert it. Should a unique key still collide, the
 *       registration runs again, up to three times in all.</li>
 *   <li>Times are database time ({@code now()}). A device expires {@code purge-after} after it
 *       was last seen; {@link JdbcStorePurger} deletes expired devices in batches and
 *       {@link #purgeStale} deletes by last-seen time in the current scope.</li>
 *   <li>Every removal is logged at INFO with the token hash and the reason, never the token.</li>
 * </ul>
 *
 * @since 1.4.0
 */
@Slf4j
public class JdbcPushDeviceRegistry implements PushDeviceRegistry, JdbcPurgeableStore {

    private static final TypeReference<Map<String, String>> ATTRIBUTES_TYPE = new TypeReference<>() {
    };
    private static final int REGISTER_ATTEMPTS = 3;
    private static final String COLUMNS = "tenant_id, user_id, installation_id, app_id, platform, token, token_hash,"
            + " locale, timezone, attributes, created_at, last_seen_at";
    private static final String SCOPE = "tenant_key = COALESCE(:tenant, '')";

    private static final String P_TENANT = "tenant";
    private static final String P_USER = "userId";
    private static final String P_INSTALLATION = "installationId";
    private static final String P_SHA = "sha";
    private static final String P_LIMIT = "limit";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final int maxDevicesPerUser;
    private final long staleMillis;
    private final long purgeMillis;

    private final String userLockSql;
    private final String tokenLockSql;
    private final String moveSql;
    private final String upsertSql;
    private final String evictSql;
    private final String activeSql;
    private final String activeForAppSql;
    private final String unregisterSql;
    private final String unregisterTokenSql;
    private final String unregisterAllSql;
    private final String removeByHashSql;
    private final String purgeStaleSql;
    private final String purgeExpiredSql;

    /**
     * @param dataSource        the database; registrations run in their own transactions on it
     * @param json              the mapper for the attributes column
     * @param tables            table names
     * @param maxDevicesPerUser most devices one user keeps in a scope, at least 1
     * @param staleAfter        a device not seen for this long is not active
     * @param purgeAfter        a device not seen for this long expires and is purged
     */
    public JdbcPushDeviceRegistry(DataSource dataSource, ObjectMapper json, JdbcStoreTables tables,
                                  int maxDevicesPerUser, Duration staleAfter, Duration purgeAfter) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.json = Objects.requireNonNull(json, "json");
        this.maxDevicesPerUser = JdbcStoreSupport.requirePositive(maxDevicesPerUser,
                "notification.push.devices.max-devices-per-user");
        this.staleMillis = JdbcStoreSupport.millis(
                JdbcStoreSupport.requirePositive(staleAfter, "notification.push.devices.stale-after"));
        this.purgeMillis = JdbcStoreSupport.millis(
                JdbcStoreSupport.requirePositive(purgeAfter, "notification.push.devices.purge-after"));
        String t = tables.pushDevice();
        String live = " AND expires_at > now()";
        String active = " AND last_seen_at >= now() - (:stale * INTERVAL '1 millisecond')" + live;
        String newestFirst = " ORDER BY last_seen_at DESC, created_at DESC, id DESC";

        this.userLockSql = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('push-user/' || COALESCE(:tenant, '')"
                + " || '/' || :userId, 0))";
        this.tokenLockSql = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('push-token/' || COALESCE(:tenant, '')"
                + " || '/' || :sha, 0))";
        this.moveSql = "DELETE FROM " + t + " WHERE " + SCOPE + " AND token_sha256 = :sha"
                + " AND NOT (user_id = :userId AND installation_id = :installationId) RETURNING token_hash";
        this.upsertSql = "INSERT INTO " + t + " (" + COLUMNS + ", token_sha256, expires_at) VALUES (:tenant, :userId,"
                + " :installationId, :appId, :platform, :token, :tokenHash, :locale, :timezone, :attributes, now(),"
                + " now(), :sha, " + nowPlusMillis("purge") + ")"
                + " ON CONFLICT (tenant_key, user_id, installation_id) DO UPDATE SET"
                + " app_id = EXCLUDED.app_id, platform = EXCLUDED.platform, token = EXCLUDED.token,"
                + " token_hash = EXCLUDED.token_hash, token_sha256 = EXCLUDED.token_sha256,"
                + " locale = EXCLUDED.locale, timezone = EXCLUDED.timezone, attributes = EXCLUDED.attributes,"
                + " last_seen_at = EXCLUDED.last_seen_at, expires_at = EXCLUDED.expires_at"
                + " RETURNING id, " + COLUMNS;
        this.evictSql = "DELETE FROM " + t + " WHERE id IN (SELECT id FROM " + t + " WHERE " + SCOPE
                + " AND user_id = :userId AND id <> :keptId" + newestFirst + " OFFSET :keep) RETURNING token_hash";
        String select = "SELECT id, " + COLUMNS + " FROM " + t + " WHERE " + SCOPE + " AND user_id = :userId";
        this.activeSql = select + active + newestFirst;
        this.activeForAppSql = select + " AND app_id = :appId" + active + newestFirst;
        this.unregisterSql = "DELETE FROM " + t + " WHERE " + SCOPE
                + " AND user_id = :userId AND installation_id = :installationId RETURNING token_hash";
        this.unregisterTokenSql = "DELETE FROM " + t + " WHERE " + SCOPE + " AND token_sha256 = :sha"
                + " RETURNING token_hash";
        this.unregisterAllSql = "DELETE FROM " + t + " WHERE " + SCOPE + " AND user_id = :userId RETURNING token_hash";
        this.removeByHashSql = "DELETE FROM " + t + " WHERE " + SCOPE + " AND token_hash = :tokenHash"
                + " RETURNING token_hash";
        this.purgeStaleSql = "DELETE FROM " + t + " WHERE " + SCOPE + " AND last_seen_at < :olderThan"
                + " RETURNING token_hash";
        this.purgeExpiredSql = "DELETE FROM " + t + " WHERE id IN (SELECT id FROM " + t
                + " WHERE expires_at <= now() LIMIT :limit FOR UPDATE SKIP LOCKED) RETURNING token_hash";
    }

    @Override
    public PushDevice register(PushDeviceRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        String tenant = JdbcStoreSupport.tenantFor(registration.tenantId());
        for (int attempt = 1; ; attempt++) {
            try {
                return Objects.requireNonNull(transactions.execute(_ -> registerInTransaction(registration, tenant)));
            } catch (DuplicateKeyException e) {
                if (attempt >= REGISTER_ATTEMPTS) {
                    throw e;
                }
                log.debug("Push device registration {} collided with a concurrent one, attempt {}",
                        registration.tokenHash(), attempt);
            }
        }
    }

    private PushDevice registerInTransaction(PushDeviceRegistration registration, String tenant) {
        String sha = PushTokens.sha256Hex(registration.token());
        // The user lock, then the token lock, always in this order: no deadlock, and a holder
        // of a token lock never waits for a user lock.
        jdbc.sql(userLockSql)
                .param(P_TENANT, tenant, Types.VARCHAR)
                .param(P_USER, registration.userId(), Types.VARCHAR)
                .query(Integer.class).single();
        jdbc.sql(tokenLockSql)
                .param(P_TENANT, tenant, Types.VARCHAR)
                .param(P_SHA, sha, Types.VARCHAR)
                .query(Integer.class).single();
        logRemovals(jdbc.sql(moveSql)
                .param(P_TENANT, tenant, Types.VARCHAR)
                .param(P_SHA, sha, Types.VARCHAR)
                .param(P_USER, registration.userId(), Types.VARCHAR)
                .param(P_INSTALLATION, registration.installationId(), Types.VARCHAR)
                .query(String.class).list(), PushDeviceRemovalReasons.MOVED);
        Stored stored = jdbc.sql(upsertSql)
                .param(P_TENANT, tenant, Types.VARCHAR)
                .param(P_USER, registration.userId(), Types.VARCHAR)
                .param(P_INSTALLATION, registration.installationId(), Types.VARCHAR)
                .param("appId", registration.appId(), Types.VARCHAR)
                .param("platform", registration.platform().name(), Types.VARCHAR)
                .param("token", registration.token(), Types.VARCHAR)
                .param("tokenHash", registration.tokenHash(), Types.VARCHAR)
                .param("locale", registration.locale(), Types.VARCHAR)
                .param("timezone", registration.timezone(), Types.VARCHAR)
                .param("attributes", attributesJson(registration.attributes()), Types.VARCHAR)
                .param(P_SHA, sha, Types.VARCHAR)
                .param("purge", purgeMillis)
                .query(this::mapStored)
                .single();
        logRemovals(jdbc.sql(evictSql)
                .param(P_TENANT, tenant, Types.VARCHAR)
                .param(P_USER, registration.userId(), Types.VARCHAR)
                .param("keptId", stored.id())
                .param("keep", maxDevicesPerUser - 1)
                .query(String.class).list(), PushDeviceRemovalReasons.EVICTED);
        return stored.device();
    }

    @Override
    public boolean unregister(String userId, String installationId) {
        if (userId == null || installationId == null) {
            return false;
        }
        return remove(jdbc.sql(unregisterSql)
                .param(P_USER, userId.strip(), Types.VARCHAR)
                .param(P_INSTALLATION, installationId.strip(), Types.VARCHAR), PushDeviceRemovalReasons.UNREGISTERED) > 0;
    }

    @Override
    public boolean unregisterToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return remove(jdbc.sql(unregisterTokenSql).param(P_SHA, PushTokens.sha256Hex(token), Types.VARCHAR),
                PushDeviceRemovalReasons.UNREGISTERED) > 0;
    }

    @Override
    public int unregisterAll(String userId) {
        if (userId == null) {
            return 0;
        }
        return remove(jdbc.sql(unregisterAllSql).param(P_USER, userId.strip(), Types.VARCHAR),
                PushDeviceRemovalReasons.UNREGISTERED);
    }

    @Override
    public List<PushDevice> activeDevices(String userId, String appId) {
        if (userId == null) {
            return List.of();
        }
        JdbcClient.StatementSpec statement = appId == null
                ? jdbc.sql(activeSql)
                : jdbc.sql(activeForAppSql).param("appId", appId.strip(), Types.VARCHAR);
        return statement
                .param(P_TENANT, JdbcStoreSupport.currentTenant(), Types.VARCHAR)
                .param(P_USER, userId.strip(), Types.VARCHAR)
                .param("stale", staleMillis)
                .query((rs, rowNum) -> mapStored(rs, rowNum).device())
                .list();
    }

    @Override
    public int removeByTokenHash(String tokenHash, String reason) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return 0;
        }
        return remove(jdbc.sql(removeByHashSql).param("tokenHash", tokenHash.strip(), Types.VARCHAR), reason);
    }

    @Override
    public int purgeStale(Instant olderThan) {
        Objects.requireNonNull(olderThan, "olderThan");
        return remove(jdbc.sql(purgeStaleSql).param("olderThan", timestamp(olderThan), Types.TIMESTAMP_WITH_TIMEZONE),
                PushDeviceRemovalReasons.STALE);
    }

    /** Deletes devices not seen for {@code purge-after}, in every scope the connection sees. */
    @Override
    public int purgeExpired(int batchSize) {
        List<String> removed = jdbc.sql(purgeExpiredSql)
                .param(P_LIMIT, JdbcStoreSupport.requirePositive(batchSize, "batchSize"))
                .query(String.class).list();
        logRemovals(removed, PushDeviceRemovalReasons.STALE);
        return removed.size();
    }

    /** Runs a scoped {@code DELETE ... RETURNING token_hash} in the current tenant scope. */
    private int remove(JdbcClient.StatementSpec statement, String reason) {
        List<String> removed = statement.param(P_TENANT, JdbcStoreSupport.currentTenant(), Types.VARCHAR)
                .query(String.class).list();
        logRemovals(removed, reason);
        return removed.size();
    }

    private record Stored(long id, PushDevice device) {
    }

    private Stored mapStored(ResultSet rs, int rowNum) throws SQLException {
        PushDevice device = new PushDevice(
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("installation_id"),
                rs.getString("app_id"),
                PushPlatform.valueOf(rs.getString("platform")),
                rs.getString("token"),
                rs.getString("token_hash"),
                rs.getString("locale"),
                rs.getString("timezone"),
                attributes(rs.getString("attributes")),
                JdbcStoreSupport.instant(rs, "created_at"),
                JdbcStoreSupport.instant(rs, "last_seen_at"));
        return new Stored(rs.getLong("id"), device);
    }

    private String attributesJson(Map<String, String> attributes) {
        try {
            return json.writeValueAsString(attributes == null ? Map.of() : attributes);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("push device attributes cannot be written as JSON", e);
        }
    }

    private Map<String, String> attributes(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(value, ATTRIBUTES_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("Unreadable push device attributes ignored: {}", e.getOriginalMessage());
            return Map.of();
        }
    }

    /** One INFO line per removal batch: the count and the reason, never a token or anything derived from it. */
    private static void logRemovals(List<String> removed, String reason) {
        if (removed.isEmpty()) {
            return;
        }
        String safeReason = reason == null ? "unspecified" : reason.replaceAll("\\p{Cntrl}", "_");
        log.info("Removed {} push device(s) ({})", removed.size(), safeReason);
    }
}
