package com.lazydevs.notification.store.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.lazydevs.notification.store.jdbc.JdbcStoreSupport.nowPlusMillis;

/**
 * PostgreSQL-backed {@link DeliveryEventStore} (DD-17) over plain SQL.
 *
 * <p>Being a {@code DeliveryEventStore}, the bean is also a
 * {@code DeliveryEventListener}, so registering it wires it into the
 * webhook fan-out.
 *
 * <p>Provider retries are de-duplicated: a second event with the same
 * {@code (tenant, providerName, providerEventId)} is ignored, as the
 * {@code DeliveryEventListener} contract recommends. Events without a
 * {@code providerEventId} are always stored.
 *
 * <p>{@code tenant_id} is the current {@code TenantContext} tenant, or
 * {@code NULL} when none is set (delivery events carry no tenant of
 * their own). Reads are not narrowed by tenant beyond what row-level
 * security, if enabled, applies. Rows past
 * {@code delivery-event-retention} are invisible and purgeable.
 */
@Slf4j
public class JdbcDeliveryEventStore implements DeliveryEventStore, JdbcPurgeableStore {

    private static final TypeReference<Map<String, String>> ATTRIBUTES = new TypeReference<>() {
    };
    private static final String SELECT_COLUMNS =
            "id, provider_name, provider_message_id, provider_event_id, status, reason, event_timestamp, attributes";

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final long retentionMillis;
    private final int maxEntries;

    private final String addSql;
    private final String snapshotSql;
    private final String findSql;
    private final String countSql;
    private final String purgeSql;

    public JdbcDeliveryEventStore(JdbcClient jdbc, ObjectMapper json, JdbcStoreTables tables,
                                  Duration retention, int maxEntries) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.retentionMillis = JdbcStoreSupport.millis(
                JdbcStoreSupport.requirePositive(retention, "notification.store.jdbc.delivery-event-retention"));
        this.maxEntries = JdbcStoreSupport.requirePositive(maxEntries, "notification.delivery-events.max-entries");
        String t = tables.deliveryEvent();
        String live = "expires_at > now()";

        this.addSql = "INSERT INTO " + t + " (tenant_id, provider_name, provider_message_id, provider_event_id,"
                + " status, reason, event_timestamp, attributes, created_at, expires_at)"
                + " VALUES (:tenant, :provider, :messageId, :eventId, :status, :reason, :eventTimestamp, :attributes,"
                + " now(), " + nowPlusMillis("retention") + ")"
                + " ON CONFLICT (tenant_key, provider_name, provider_event_id) DO NOTHING";
        this.snapshotSql = "SELECT " + SELECT_COLUMNS + " FROM " + t + " WHERE " + live
                + " ORDER BY created_at DESC, id DESC LIMIT :limit";
        this.findSql = "SELECT " + SELECT_COLUMNS + " FROM " + t
                + " WHERE provider_name = :provider AND provider_message_id = :messageId AND " + live
                + " ORDER BY created_at DESC, id DESC LIMIT :limit";
        this.countSql = "SELECT COUNT(*) FROM " + t + " WHERE " + live;
        this.purgeSql = "DELETE FROM " + t + " WHERE id IN (SELECT id FROM " + t
                + " WHERE expires_at <= now() LIMIT :limit FOR UPDATE SKIP LOCKED)";
    }

    @Override
    public void add(DeliveryEvent event) {
        // SPI contract: never throw, or the webhook would answer 5xx and
        // the provider would retry forever.
        try {
            jdbc.sql(addSql)
                    .param("tenant", JdbcStoreSupport.currentTenant(), Types.VARCHAR)
                    .param("provider", event.providerName(), Types.VARCHAR)
                    .param("messageId", event.providerMessageId(), Types.VARCHAR)
                    .param("eventId", event.providerEventId(), Types.VARCHAR)
                    .param("status", event.status().name(), Types.VARCHAR)
                    .param("reason", event.reason(), Types.VARCHAR)
                    .param("eventTimestamp", JdbcStoreSupport.timestamp(event.timestamp()))
                    .param("attributes", json.writeValueAsString(event.attributes()), Types.VARCHAR)
                    .param("retention", retentionMillis)
                    .update();
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise delivery event; swallowing: {}", e.toString());
        } catch (RuntimeException e) {
            log.warn("Failed to record delivery event; swallowing: {}", e.toString());
        }
    }

    /** Most recent first, at most {@code notification.delivery-events.max-entries} rows. */
    @Override
    public Optional<List<DeliveryEvent>> snapshot() {
        return Optional.of(jdbc.sql(snapshotSql)
                .param("limit", maxEntries)
                .query(this::mapEvent)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .toList());
    }

    /** Most recent first, at most {@code notification.delivery-events.max-entries} rows. */
    @Override
    public Optional<List<DeliveryEvent>> findByProviderMessageId(String providerName, String providerMessageId) {
        if (providerName == null || providerMessageId == null) {
            return Optional.of(List.of());
        }
        return Optional.of(jdbc.sql(findSql)
                .param("provider", providerName, Types.VARCHAR)
                .param("messageId", providerMessageId, Types.VARCHAR)
                .param("limit", maxEntries)
                .query(this::mapEvent)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .toList());
    }

    /**
     * Live rows across all tenants, the same global count the in-memory and
     * Redis stores report. {@code TenantContext} does not narrow it;
     * row-level security, if the host enables it, does.
     */
    @Override
    public int size() {
        Long count = jdbc.sql(countSql).query(Long.class).single();
        return (int) Math.min(Integer.MAX_VALUE, count == null ? 0L : count);
    }

    @Override
    public int purgeExpired(int batchSize) {
        return jdbc.sql(purgeSql)
                .param("limit", JdbcStoreSupport.requirePositive(batchSize, "batchSize"))
                .update();
    }

    private DeliveryEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new DeliveryEvent(
                    JdbcStoreSupport.instant(rs, "event_timestamp"),
                    rs.getString("provider_name"),
                    rs.getString("provider_message_id"),
                    rs.getString("provider_event_id"),
                    DeliveryStatus.valueOf(rs.getString("status")),
                    rs.getString("reason"),
                    json.readValue(rs.getString("attributes"), ATTRIBUTES));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("Skipping malformed delivery-event row id={}: {}", rs.getLong("id"), e.getMessage());
            return null;
        }
    }
}
