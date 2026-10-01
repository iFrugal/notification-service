package com.lazydevs.notification.store.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Shared fixtures for the Testcontainers integration tests. Each IT class
 * owns one container (started in {@code @BeforeAll}, stopped in
 * {@code @AfterAll}) and creates its tables from {@link JdbcStoreSchema}.
 */
final class PostgresTestSupport {

    static final ObjectMapper JSON = JdbcStoreJson.create();

    private PostgresTestSupport() {
    }

    @SuppressWarnings("resource") // stopped by the owning test class
    static PostgreSQLContainer startPostgres() {
        PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
        container.start();
        return container;
    }

    static HikariDataSource dataSource(PostgreSQLContainer pg) {
        return dataSource(pg, pg.getUsername(), pg.getPassword(), null);
    }

    static HikariDataSource dataSource(PostgreSQLContainer pg, String user, String password, String initSql) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(pg.getJdbcUrl());
        config.setUsername(user);
        config.setPassword(password);
        // Enough for the 20-thread race tests to really run concurrently.
        config.setMaximumPoolSize(24);
        if (initSql != null) {
            config.setConnectionInitSql(initSql);
        }
        return new HikariDataSource(config);
    }

    static void createTables(JdbcClient jdbc, JdbcStoreTables tables) {
        JdbcStoreSchema.postgresql(tables).statements().forEach(sql -> jdbc.sql(sql).update());
    }

    static void truncate(JdbcClient jdbc, JdbcStoreTables tables) {
        jdbc.sql("TRUNCATE " + tables.idempotency() + ", " + tables.deadLetter() + ", " + tables.deliveryEvent())
                .update();
    }

    /** Pushes every row of {@code table} matching {@code where} into the past. */
    static int expire(JdbcClient jdbc, String table, String where) {
        return jdbc.sql("UPDATE " + table + " SET expires_at = now() - INTERVAL '1 second' WHERE " + where)
                .update();
    }

    static NotificationRequest request(String tenantId, String requestId) {
        return NotificationRequest.builder()
                .requestId(requestId)
                .tenantId(tenantId)
                .callerId("billing")
                .notificationType("ORDER_CONFIRMATION")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient("r-1", "user@example.com", List.of("cc@example.com"),
                        List.of(), null, "Your order"))
                .templateData(Map.of("orderId", "o-42", "total", 12))
                .metadata(Map.of("source", "it"))
                .build();
    }

    static DeadLetterEntry deadLetter(String tenantId, String requestId) {
        return deadLetter(tenantId, requestId, 3);
    }

    static DeadLetterEntry deadLetter(String tenantId, String requestId, int attempts) {
        NotificationRequest request = request(tenantId, requestId);
        return new DeadLetterEntry(
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                request,
                NotificationResponse.failure(request, "smtp", "SMTP_550", "mailbox unavailable"),
                attempts,
                FailureType.TRANSIENT);
    }

    static DeliveryEvent deliveryEvent(String provider, String messageId, String eventId, DeliveryStatus status) {
        return new DeliveryEvent(
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                provider, messageId, eventId, status,
                status == DeliveryStatus.DELIVERED ? null : "rejected by carrier",
                Map.of("messagestatus", status.name().toLowerCase()));
    }
}
