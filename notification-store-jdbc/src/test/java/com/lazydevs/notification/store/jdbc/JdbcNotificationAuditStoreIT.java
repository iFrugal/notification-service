package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationAudit;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.NotificationView;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.util.MessageScrubber;
import com.lazydevs.notification.core.audit.StoreBackedAuditService;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.idempotency.IdempotencyFailurePolicy;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.service.DefaultNotificationService;
import com.lazydevs.notification.core.service.NotificationServiceExtensions;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcNotificationAuditStore} on a real PostgreSQL, and the DD-33
 * status view ({@code NotificationService.find}) over it across a send's
 * lifecycle.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcNotificationAuditStoreIT {

    private static final JdbcStoreTables TABLES = JdbcStoreTables.defaults();

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;

    private JdbcNotificationAuditStore store;

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
        store = new JdbcNotificationAuditStore(jdbc, PostgresTestSupport.JSON, TABLES, Duration.ofDays(90));
    }

    // ---------------------------------------------------------------- store

    @Test
    void insertAndFindRoundTripEveryColumn() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        NotificationAudit audit = NotificationAudit.builder()
                .tenantId("acme").requestId("r-1").correlationId("c-1").callerId("billing")
                .idempotencyKey("k-1").replayOf("r-0").notificationType("WELCOME").channel(Channel.EMAIL)
                .provider("smtp").templateId("welcome").recipientSummary("to=u***@example.com cc=0 bcc=0")
                .recipientCount(3).status(NotificationStatus.PROCESSING).metadata(Map.of("source", "it"))
                .receivedAt(now).build();

        store.insert(audit);
        NotificationAudit read = store.find("acme", "r-1").orElseThrow();

        assertThat(read).usingRecursiveComparison().ignoringFields("id", "updatedAt").isEqualTo(audit);
        assertThat(read.getId()).isNotBlank();
        assertThat(read.getUpdatedAt()).isNotNull();
        assertThat(store.find("globex", "r-1")).isEmpty();
        assertThat(store.find(null, "r-1")).isEmpty();
    }

    @Test
    void nullTenantIsOneScope_andNullCountReadsAsNull() {
        store.insert(processing(null, "r-null", null, null));

        NotificationAudit read = store.find(null, "r-null").orElseThrow();
        assertThat(read.getTenantId()).isNull();
        assertThat(read.getRecipientCount()).isNull();
        assertThat(read.getMetadata()).isNull();
    }

    @Test
    void secondInsertOfTheSameRequestReplacesTheRow() {
        store.insert(processing("acme", "r-dup", "k", null));
        store.recordOutcome(NotificationResponse.failure(PostgresTestSupport.request("acme", "r-dup"), "smtp",
                "E", "m"));
        store.insert(processing("acme", "r-dup", "k", null));

        NotificationAudit read = store.find("acme", "r-dup").orElseThrow();
        assertThat(read.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(read.getErrorCode()).isNull();
        assertThat(read.getFailedAt()).isNull();
    }

    @Test
    void recordOutcomeFollowsApplyOutcome() {
        store.insert(processing("acme", "r-out", null, "smtp"));
        NotificationRequest request = PostgresTestSupport.request("acme", "r-out");
        Instant sentAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        assertThat(store.recordOutcome(NotificationResponse.sent(request, null, "m-1", sentAt, sentAt))).isTrue();
        NotificationAudit sent = store.find("acme", "r-out").orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(sent.getProvider()).as("kept when the outcome names none").isEqualTo("smtp");
        assertThat(sent.getProviderMessageId()).isEqualTo("m-1");
        assertThat(sent.getSentAt()).isEqualTo(sentAt);
        assertThat(sent.getFailedAt()).isNull();

        NotificationResponse failed = NotificationResponse.failed(request, "ses", null, "SES_X", "[email] bounced",
                sentAt).withFailureType(FailureType.PERMANENT);
        assertThat(store.recordOutcome(failed)).isTrue();
        NotificationAudit read = store.find("acme", "r-out").orElseThrow();
        assertThat(read.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(read.getProvider()).isEqualTo("ses");
        assertThat(read.getProviderMessageId()).as("kept when the outcome has none").isEqualTo("m-1");
        assertThat(read.getFailureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(read.getErrorMessage()).isEqualTo("[email] bounced");
        assertThat(read.getFailedAt()).isNotNull().isEqualTo(read.getProcessedAt());
        assertThat(read.getSentAt()).as("kept").isEqualTo(sentAt);

        assertThat(store.recordOutcome(NotificationResponse.failure(
                PostgresTestSupport.request("acme", "absent"), "smtp", "E", "m"))).isFalse();
    }

    @Test
    void unknownStatusValuesFromANewerVersionStillRead() {
        store.insert(processing("acme", "r-new", null, null));
        jdbc.sql("UPDATE " + TABLES.audit() + " SET status = 'READ', failure_type = 'SOMETHING_NEW',"
                + " channel = 'CARRIER_PIGEON', metadata = '{broken'").update();

        NotificationAudit read = store.find("acme", "r-new").orElseThrow();
        assertThat(read.getStatus()).isNull();
        assertThat(read.getFailureType()).isEqualTo(FailureType.UNKNOWN);
        assertThat(read.getChannel()).isNull();
        assertThat(read.getMetadata()).isNull();
    }

    @Test
    void findByIdempotencyKeyReturnsTheLatestRowOfThatScope() {
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        store.insert(processing("acme", "r-a", "k", null).toBuilder().callerId("billing").receivedAt(t0).build());
        store.insert(processing("acme", "r-b", "k", null).toBuilder().callerId("billing")
                .receivedAt(t0.plusSeconds(5)).build());
        store.insert(processing("acme", "r-c", "k", null).toBuilder().callerId(null)
                .receivedAt(t0.plusSeconds(9)).build());

        assertThat(store.findByIdempotencyKey("acme", "billing", "k").orElseThrow().getRequestId()).isEqualTo("r-b");
        assertThat(store.findByIdempotencyKey("acme", null, "k").orElseThrow().getRequestId()).isEqualTo("r-c");
        assertThat(store.findByIdempotencyKey("globex", "billing", "k")).isEmpty();
    }

    @Test
    void findRecentIsNewestFirstAndBounded() {
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        for (int i = 0; i < 5; i++) {
            store.insert(processing("acme", "r-" + i, null, null).toBuilder().receivedAt(t0.plusSeconds(i)).build());
        }
        store.insert(processing("globex", "g-1", null, null));

        assertThat(store.findRecent("acme", 3)).extracting(NotificationAudit::getRequestId)
                .containsExactly("r-4", "r-3", "r-2");
    }

    @Test
    void expiredRowsAreInvisibleAndPurgedInBatches() {
        for (int i = 0; i < 5; i++) {
            store.insert(processing("acme", "r-" + i, "k", null));
        }
        store.insert(processing("acme", "live", null, null));
        PostgresTestSupport.expire(jdbc, TABLES.audit(), "request_id LIKE 'r-%'");

        assertThat(store.find("acme", "r-1")).isEmpty();
        assertThat(store.findByIdempotencyKey("acme", null, "k")).isEmpty();
        assertThat(store.recordOutcome(NotificationResponse.failure(
                PostgresTestSupport.request("acme", "r-1"), "smtp", "E", "m"))).isFalse();
        assertThat(store.purgeExpired(2)).isEqualTo(2);
        assertThat(new JdbcStorePurger(java.util.List.of(store), 2).purgeAll()).isEqualTo(3);
        assertThat(store.find("acme", "live")).isPresent();
    }

    // ---------------------------------------------------------------- status view lifecycle

    @Test
    void statusView_processingThenSentThenDelivered() {
        JdbcDeliveryEventStore events = new JdbcDeliveryEventStore(jdbc, PostgresTestSupport.JSON, TABLES,
                Duration.ofDays(1), 100);
        NotificationProvider provider = mock(NotificationProvider.class);
        when(provider.getProviderName()).thenReturn("ses");
        DefaultNotificationService service = service(provider, events, new NotificationProperties());
        NotificationRequest request = keyed("r-life", "k-life");
        AtomicReference<NotificationView> duringSend = new AtomicReference<>();
        when(provider.send(any(), any())).thenAnswer(inv -> {
            duringSend.set(service.find("acme", "r-life").orElseThrow());
            return SendResult.success("ses-msg-1");
        });

        service.send(request);

        assertThat(duringSend.get().status()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(duringSend.get().sentAt()).isNull();
        NotificationView sent = service.find("acme", "r-life").orElseThrow();
        assertThat(sent.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(sent.provider()).isEqualTo("ses");
        assertThat(sent.providerMessageId()).isEqualTo("ses-msg-1");
        assertThat(sent.sentAt()).isNotNull();
        assertThat(sent.deliveryStatus()).isNull();
        assertThat(sent.recipientCount()).isEqualTo(2);
        assertThat(sent.metadata()).containsExactly(Map.entry("source", "it"));
        assertThat(sent.idempotencyKey()).isEqualTo("k-life");
        assertThat(sent.callerId()).isEqualTo("billing");

        events.add(PostgresTestSupport.deliveryEvent("ses", "ses-msg-1", "ev-1", DeliveryStatus.DELIVERED));
        NotificationView delivered = service.find("acme", "r-life").orElseThrow();
        assertThat(delivered.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(delivered.deliveryStatus()).isEqualTo("DELIVERED");
        assertThat(delivered.deliveredAt()).isNotNull().isEqualTo(delivered.deliveryStatusAt());

        // An idempotent replay creates no row; the key still shows the original.
        NotificationResponse replay = service.send(keyed("r-replay", "k-life"));
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(service.find("acme", "r-replay")).isEmpty();
        assertThat(service.findByIdempotencyKey("acme", "billing", "k-life").orElseThrow().requestId())
                .isEqualTo("r-life");
    }

    @Test
    void statusView_permanentFailureIsScrubbedAndClassified() {
        NotificationProvider provider = mock(NotificationProvider.class);
        when(provider.getProviderName()).thenReturn("ses");
        when(provider.send(any(), any())).thenReturn(SendResult.failure("SES_REJECT",
                "Address jane.doe@example.com is on the suppression list", FailureType.PERMANENT, "ses-msg-2"));
        DefaultNotificationService service = service(provider, null, new NotificationProperties());

        service.send(keyed("r-fail", "k-fail"));

        NotificationView view = service.find("acme", "r-fail").orElseThrow();
        assertThat(view.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(view.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(view.errorCode()).isEqualTo("SES_REJECT");
        assertThat(view.errorMessage()).doesNotContain("jane.doe");
        assertThat(view.failedAt()).isNotNull();
        assertThat(view.providerMessageId()).isEqualTo("ses-msg-2");
    }

    @Test
    void statusView_ambiguousAfterTheInProgressTimeoutSettles() {
        NotificationProperties properties = new NotificationProperties();
        properties.getIdempotency().setInProgressTimeout(Duration.ofMinutes(5));
        properties.getIdempotency().setFailurePolicy(IdempotencyFailurePolicy.RELEASE_TRANSIENT);
        JdbcIdempotencyStore idempotency = new JdbcIdempotencyStore(jdbc, PostgresTestSupport.JSON, TABLES,
                Duration.ofHours(24), 100);
        StoreBackedAuditService audit = new StoreBackedAuditService(store, "default", Clock.systemUTC());
        // A first attempt claimed the key and recorded its audit row, then its process died.
        NotificationRequest stuck = keyed("r-stuck", "k-stuck");
        idempotency.markInProgress(new IdempotencyKey("acme", "billing", "k-stuck"), "r-stuck", "fp");
        audit.recordReceived(stuck);
        jdbc.sql("UPDATE " + TABLES.idempotency() + " SET recorded_at = now() - INTERVAL '10 minutes'").update();
        NotificationProvider provider = mock(NotificationProvider.class);
        DefaultNotificationService service = new DefaultNotificationService(properties, registry(provider),
                templates(), audit, Optional.of(idempotency), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), NotificationServiceExtensions.of(MessageScrubber.placeholders(), Clock.systemUTC()));

        NotificationResponse replay = service.send(keyed("r-retry", "k-stuck"));

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.errorCode()).isEqualTo("IN_PROGRESS_TIMEOUT");
        NotificationView view = service.findByIdempotencyKey("acme", "billing", "k-stuck").orElseThrow();
        assertThat(view.requestId()).isEqualTo("r-stuck");
        assertThat(view.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(view.failureType()).isEqualTo(FailureType.AMBIGUOUS);
        assertThat(view.errorCode()).isEqualTo("IN_PROGRESS_TIMEOUT");
        assertThat(view.failedAt()).isNotNull();
    }

    // ---------------------------------------------------------------- helpers

    private DefaultNotificationService service(NotificationProvider provider, JdbcDeliveryEventStore events,
                                               NotificationProperties properties) {
        JdbcIdempotencyStore idempotency = new JdbcIdempotencyStore(jdbc, PostgresTestSupport.JSON, TABLES,
                Duration.ofHours(24), 100);
        return new DefaultNotificationService(properties, registry(provider), templates(),
                new StoreBackedAuditService(store, "default", Clock.systemUTC()), Optional.of(idempotency),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                new NotificationServiceExtensions(MessageScrubber.placeholders(), Clock.systemUTC(), events));
    }

    private static ProviderRegistry registry(NotificationProvider provider) {
        ProviderRegistry registry = mock(ProviderRegistry.class);
        when(registry.getProvider(anyString(), any(), any())).thenReturn(provider);
        return registry;
    }

    private static NotificationTemplateEngine templates() {
        NotificationTemplateEngine templates = mock(NotificationTemplateEngine.class);
        when(templates.render(any())).thenReturn(new RenderedContent("subj", "body", null, "ORDER_CONFIRMATION"));
        return templates;
    }

    private static NotificationRequest keyed(String requestId, String key) {
        NotificationRequest request = PostgresTestSupport.request("acme", requestId);
        request.setIdempotencyKey(key);
        return request;
    }

    private static NotificationAudit processing(String tenant, String requestId, String key, String provider) {
        return NotificationAudit.builder()
                .tenantId(tenant)
                .requestId(requestId)
                .idempotencyKey(key)
                .provider(provider)
                .status(NotificationStatus.PROCESSING)
                .receivedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                .build();
    }
}
