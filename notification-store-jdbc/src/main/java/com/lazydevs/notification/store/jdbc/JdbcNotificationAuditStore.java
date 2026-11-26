package com.lazydevs.notification.store.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.audit.NotificationAuditStore;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationAudit;
import com.lazydevs.notification.api.model.NotificationResponse;
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
import static com.lazydevs.notification.store.jdbc.JdbcStoreSupport.timestamp;

/**
 * PostgreSQL-backed {@link NotificationAuditStore} (DD-33) over plain SQL,
 * the persistence behind {@code NotificationService.find}.
 *
 * <p>One row per {@code (tenant, request id)}, unique over the generated
 * {@code tenant_key} ({@code COALESCE(tenant_id, '')}). A second insert for
 * the same request id replaces the row. Lookups by idempotency key use the
 * {@code (tenant_key, caller_key, idem_key, received_at)} index and return
 * the most recently received row.
 *
 * <p>Status columns are plain text without a check constraint, so statuses
 * added by later versions need no migration; an unknown value reads as
 * {@code null} ({@code FailureType} as {@code UNKNOWN}).
 *
 * <p>Rows expire {@code notification.audit.retention-days} after they were
 * created and are then invisible and purgeable through
 * {@link JdbcStorePurger}. The store never holds recipient addresses: the
 * summary is masked and the count is a number. Errors propagate; the core
 * audit service logs and swallows write failures.
 */
@Slf4j
public class JdbcNotificationAuditStore implements NotificationAuditStore, JdbcPurgeableStore {

    private static final TypeReference<Map<String, String>> METADATA = new TypeReference<>() {
    };
    private static final String COLUMNS = "tenant_id, request_id, correlation_id, caller_id, idem_key, replay_of,"
            + " notification_type, channel, provider, template_id, recipient_summary, recipient_count, status,"
            + " failure_type, provider_message_id, error_code, error_message, metadata, received_at, processed_at,"
            + " sent_at, delivered_at, failed_at, updated_at, created_at, expires_at";
    private static final String SELECT = "SELECT id, " + COLUMNS + " FROM ";

    private static final String TENANT = "tenant";
    private static final String REQUEST_ID = "requestId";
    private static final String PROVIDER = "provider";
    private static final String STATUS = "status";
    private static final String LIMIT = "limit";

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final long retentionMillis;

    private final String insertSql;
    private final String outcomeSql;
    private final String findSql;
    private final String findByKeySql;
    private final String recentSql;
    private final String purgeSql;

    public JdbcNotificationAuditStore(JdbcClient jdbc, ObjectMapper json, JdbcStoreTables tables, Duration retention) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.retentionMillis = JdbcStoreSupport.millis(
                JdbcStoreSupport.requirePositive(retention, "notification.audit.retention-days"));
        String t = tables.audit();
        String live = " AND expires_at > now()";

        this.insertSql = "INSERT INTO " + t + " (" + COLUMNS + ") VALUES (:tenant, :requestId, :correlationId,"
                + " :callerId, :idemKey, :replayOf, :notificationType, :channel, :provider, :templateId,"
                + " :recipientSummary, :recipientCount, :status, :failureType, :providerMessageId, :errorCode,"
                + " :errorMessage, :metadata, :receivedAt, :processedAt, :sentAt, :deliveredAt, :failedAt,"
                + " now(), now(), " + nowPlusMillis("retention") + ")"
                + " ON CONFLICT (tenant_key, request_id) DO UPDATE SET"
                + " correlation_id = EXCLUDED.correlation_id, caller_id = EXCLUDED.caller_id,"
                + " idem_key = EXCLUDED.idem_key, replay_of = EXCLUDED.replay_of,"
                + " notification_type = EXCLUDED.notification_type, channel = EXCLUDED.channel,"
                + " provider = EXCLUDED.provider, template_id = EXCLUDED.template_id,"
                + " recipient_summary = EXCLUDED.recipient_summary, recipient_count = EXCLUDED.recipient_count,"
                + " status = EXCLUDED.status, failure_type = EXCLUDED.failure_type,"
                + " provider_message_id = EXCLUDED.provider_message_id, error_code = EXCLUDED.error_code,"
                + " error_message = EXCLUDED.error_message, metadata = EXCLUDED.metadata,"
                + " received_at = EXCLUDED.received_at, processed_at = EXCLUDED.processed_at,"
                + " sent_at = EXCLUDED.sent_at, delivered_at = EXCLUDED.delivered_at,"
                + " failed_at = EXCLUDED.failed_at, updated_at = EXCLUDED.updated_at,"
                + " created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at";

        // NotificationAudit.applyOutcome, in SQL.
        this.outcomeSql = "UPDATE " + t + " SET status = :status,"
                + " provider = COALESCE(:provider, provider),"
                + " provider_message_id = COALESCE(:providerMessageId, provider_message_id),"
                + " error_code = :errorCode, error_message = :errorMessage, failure_type = :failureType,"
                + " processed_at = COALESCE(:processedAt, now()),"
                + " sent_at = COALESCE(:sentAt, sent_at),"
                + " failed_at = CASE WHEN :failed THEN COALESCE(:processedAt, now()) END,"
                + " updated_at = now()"
                + " WHERE tenant_key = COALESCE(:tenant, '') AND request_id = :requestId" + live;

        this.findSql = SELECT + t + " WHERE tenant_key = COALESCE(:tenant, '') AND request_id = :requestId" + live;

        this.findByKeySql = SELECT + t + " WHERE tenant_key = COALESCE(:tenant, '')"
                + " AND caller_key = COALESCE(:callerId, '') AND idem_key = :idemKey" + live
                + " ORDER BY received_at DESC, id DESC LIMIT 1";

        this.recentSql = SELECT + t + " WHERE tenant_key = COALESCE(:tenant, '')" + live
                + " ORDER BY received_at DESC, id DESC LIMIT :limit";

        this.purgeSql = "DELETE FROM " + t + " WHERE id IN (SELECT id FROM " + t
                + " WHERE expires_at <= now() LIMIT :limit FOR UPDATE SKIP LOCKED)";
    }

    @Override
    public void insert(NotificationAudit audit) {
        jdbc.sql(insertSql)
                .param(TENANT, audit.getTenantId(), Types.VARCHAR)
                .param(REQUEST_ID, Objects.requireNonNull(audit.getRequestId(), REQUEST_ID), Types.VARCHAR)
                .param("correlationId", audit.getCorrelationId(), Types.VARCHAR)
                .param("callerId", audit.getCallerId(), Types.VARCHAR)
                .param("idemKey", audit.getIdempotencyKey(), Types.VARCHAR)
                .param("replayOf", audit.getReplayOf(), Types.VARCHAR)
                .param("notificationType", audit.getNotificationType(), Types.VARCHAR)
                .param("channel", name(audit.getChannel()), Types.VARCHAR)
                .param(PROVIDER, audit.getProvider(), Types.VARCHAR)
                .param("templateId", audit.getTemplateId(), Types.VARCHAR)
                .param("recipientSummary", audit.getRecipientSummary(), Types.VARCHAR)
                .param("recipientCount", audit.getRecipientCount(), Types.INTEGER)
                .param(STATUS, name(Objects.requireNonNull(audit.getStatus(), STATUS)), Types.VARCHAR)
                .param("failureType", name(audit.getFailureType()), Types.VARCHAR)
                .param("providerMessageId", audit.getProviderMessageId(), Types.VARCHAR)
                .param("errorCode", audit.getErrorCode(), Types.VARCHAR)
                .param("errorMessage", audit.getErrorMessage(), Types.VARCHAR)
                .param("metadata", metadataJson(audit.getMetadata()), Types.VARCHAR)
                .param("receivedAt", timestamp(Objects.requireNonNull(audit.getReceivedAt(), "receivedAt")),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("processedAt", timestamp(audit.getProcessedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("sentAt", timestamp(audit.getSentAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("deliveredAt", timestamp(audit.getDeliveredAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("failedAt", timestamp(audit.getFailedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("retention", retentionMillis)
                .update();
    }

    @Override
    public boolean recordOutcome(NotificationResponse outcome) {
        return jdbc.sql(outcomeSql)
                .param(STATUS, name(Objects.requireNonNull(outcome.status(), STATUS)), Types.VARCHAR)
                .param(PROVIDER, outcome.provider(), Types.VARCHAR)
                .param("providerMessageId", outcome.providerMessageId(), Types.VARCHAR)
                .param("errorCode", outcome.errorCode(), Types.VARCHAR)
                .param("errorMessage", outcome.errorMessage(), Types.VARCHAR)
                .param("failureType", name(outcome.failureType()), Types.VARCHAR)
                .param("processedAt", timestamp(outcome.processedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("sentAt", timestamp(outcome.sentAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("failed", NotificationAudit.isFailure(outcome.status()), Types.BOOLEAN)
                .param(TENANT, outcome.tenantId(), Types.VARCHAR)
                .param(REQUEST_ID, outcome.requestId(), Types.VARCHAR)
                .update() > 0;
    }

    @Override
    public Optional<NotificationAudit> find(String tenantId, String requestId) {
        return jdbc.sql(findSql)
                .param(TENANT, tenantId, Types.VARCHAR)
                .param(REQUEST_ID, requestId, Types.VARCHAR)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public Optional<NotificationAudit> findByIdempotencyKey(String tenantId, String callerId, String idempotencyKey) {
        return jdbc.sql(findByKeySql)
                .param(TENANT, tenantId, Types.VARCHAR)
                .param("callerId", callerId, Types.VARCHAR)
                .param("idemKey", idempotencyKey, Types.VARCHAR)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public List<NotificationAudit> findRecent(String tenantId, int limit) {
        return jdbc.sql(recentSql)
                .param(TENANT, tenantId, Types.VARCHAR)
                .param(LIMIT, JdbcStoreSupport.requirePositive(limit, LIMIT))
                .query(this::mapRow)
                .list();
    }

    @Override
    public int purgeExpired(int batchSize) {
        return jdbc.sql(purgeSql)
                .param(LIMIT, JdbcStoreSupport.requirePositive(batchSize, "batchSize"))
                .update();
    }

    private NotificationAudit mapRow(ResultSet rs, int rowNum) throws SQLException {
        int count = rs.getInt("recipient_count");
        Integer recipientCount = rs.wasNull() ? null : count;
        return NotificationAudit.builder()
                .id(String.valueOf(rs.getLong("id")))
                .tenantId(rs.getString("tenant_id"))
                .requestId(rs.getString("request_id"))
                .correlationId(rs.getString("correlation_id"))
                .callerId(rs.getString("caller_id"))
                .idempotencyKey(rs.getString("idem_key"))
                .replayOf(rs.getString("replay_of"))
                .notificationType(rs.getString("notification_type"))
                .channel(JdbcStoreSupport.enumOrDefault(Channel.class, rs.getString("channel"), null))
                .provider(rs.getString(PROVIDER))
                .templateId(rs.getString("template_id"))
                .recipientSummary(rs.getString("recipient_summary"))
                .recipientCount(recipientCount)
                .status(JdbcStoreSupport.enumOrDefault(NotificationStatus.class, rs.getString(STATUS), null))
                .failureType(failureType(rs.getString("failure_type")))
                .providerMessageId(rs.getString("provider_message_id"))
                .errorCode(rs.getString("error_code"))
                .errorMessage(rs.getString("error_message"))
                .metadata(metadata(rs.getString("metadata")).orElse(null))
                .receivedAt(JdbcStoreSupport.instant(rs, "received_at"))
                .processedAt(JdbcStoreSupport.instant(rs, "processed_at"))
                .sentAt(JdbcStoreSupport.instant(rs, "sent_at"))
                .deliveredAt(JdbcStoreSupport.instant(rs, "delivered_at"))
                .failedAt(JdbcStoreSupport.instant(rs, "failed_at"))
                .updatedAt(JdbcStoreSupport.instant(rs, "updated_at"))
                .build();
    }

    private static FailureType failureType(String value) {
        return value == null ? null : JdbcStoreSupport.enumOrDefault(FailureType.class, value, FailureType.UNKNOWN);
    }

    private String metadataJson(Map<String, String> metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            return json.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            // A Map<String, String> always serialises; a programming error otherwise.
            throw new IllegalStateException("Failed to serialise audit metadata", e);
        }
    }

    /** Stored metadata JSON; empty when the column is null or malformed (read as absent, not as an empty map). */
    private Optional<Map<String, String>> metadata(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(value, METADATA));
        } catch (JsonProcessingException e) {
            log.warn("Malformed audit metadata JSON; reading it as absent: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
