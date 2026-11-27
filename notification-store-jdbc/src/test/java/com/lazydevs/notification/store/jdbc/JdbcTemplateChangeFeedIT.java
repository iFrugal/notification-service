package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.template.TemplateChangeListener;
import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.api.template.TemplateVersion;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import com.lazydevs.notification.core.template.ResourceLoaderTemplateSource;
import com.lazydevs.notification.core.template.TemplateChangeDispatcher;
import com.lazydevs.notification.core.template.admin.TemplateAdminService;
import com.lazydevs.notification.core.template.admin.TemplateSampleProvider;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-38: two engines (two replicas) share one database. A publish through one is rendered by
 * it at once, and by the other after its change feed polls; until then the other serves its
 * cached copy. The feed also survives event ids that commit out of order.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcTemplateChangeFeedIT {

    private static final JdbcStoreTables TABLES = JdbcStoreTables.defaults();
    private static final TemplateKey WELCOME = TemplateKey.of(null, "email", "WELCOME");
    private static final Map<String, Object> MODEL = Map.of("name", "Ada");

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;

    @TempDir
    Path dir;

    private final List<JdbcTemplateChangeFeed> feeds = new ArrayList<>();
    private Replica a;
    private Replica b;

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
    void setUp() throws IOException {
        PostgresTestSupport.truncate(jdbc, TABLES);
        Path file = dir.resolve("default/email/WELCOME.ftl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "[SUBJECT]built-in ${name}[/SUBJECT]body");
        a = new Replica(dir, Duration.ofHours(1));
        b = new Replica(dir, Duration.ofMillis(200));
        feeds.add(a.feed);
        feeds.add(b.feed);
    }

    @AfterEach
    void stopFeeds() {
        feeds.forEach(JdbcTemplateChangeFeed::stop);
    }

    /** One process: its own engine, store, admin service and feed over the shared database. */
    private static final class Replica {
        final NotificationTemplateEngine engine;
        final TemplateAdminService admin;
        final JdbcTemplateChangeFeed feed;

        Replica(Path dir, Duration pollInterval) {
            NotificationProperties properties = new NotificationProperties();
            properties.getTemplate().setBasePath(dir.toUri().toString());
            properties.getTemplate().setLegacyHelpers(false);
            JdbcTemplateStore store = new JdbcTemplateStore(dataSource, TABLES, 10_000, null);
            ResourceLoaderTemplateSource source =
                    new ResourceLoaderTemplateSource(new DefaultResourceLoader(), properties);
            engine = new NotificationTemplateEngine(properties, source, store);
            TemplateChangeDispatcher dispatcher = new TemplateChangeDispatcher(() -> Stream.of(engine));
            feed = new JdbcTemplateChangeFeed(jdbc, TABLES, pollInterval, null);
            dispatcher.listenTo(feed);
            feed.start();
            admin = new TemplateAdminService(engine, source, store, TemplateAdminService.Options.defaults(),
                    TemplateSampleProvider.NONE, dispatcher::templateChanged);
        }

        String subject() {
            return engine.render(NotificationRequest.builder().tenantId("acme").channel(Channel.EMAIL)
                    .templateId("WELCOME").templateData(MODEL).build()).subject();
        }

        void publish(String subject) {
            long revision = admin.get(WELCOME).draft() == null ? 0 : admin.get(WELCOME).draft().revision();
            TemplateVersion draft = admin.saveDraft(WELCOME, "[SUBJECT]" + subject + " ${name}[/SUBJECT]body", MODEL,
                    revision, "ann", null);
            admin.publish(WELCOME, draft.revision(), "ann", "change");
        }
    }

    @Test
    void publishReachesTheOtherReplicaWithinOnePollInterval() throws InterruptedException {
        assertThat(a.subject()).isEqualTo("built-in Ada");
        assertThat(b.subject()).isEqualTo("built-in Ada");
        // Subscribed after b's engine, so it fires once b has evicted
        CountDownLatch bHeardThePublish = new CountDownLatch(1);
        b.feed.subscribe(new TemplateChangeListener() {
            @Override
            public void templateChanged(TemplateKey key) {
                if (key.equals(WELCOME) && bHeardThePublish.getCount() > 0) {
                    bHeardThePublish.countDown();
                }
            }
        });

        a.publish("v1");

        assertThat(a.subject()).as("the publishing replica evicts at once").isEqualTo("v1 Ada");
        assertThat(bHeardThePublish.await(10, TimeUnit.SECONDS)).as("b's feed delivered the publish").isTrue();
        assertThat(b.subject()).isEqualTo("v1 Ada");

        b.admin.reset(WELCOME, "bob", "back");
        assertThat(b.subject()).isEqualTo("built-in Ada");
        // a polls only every hour: it keeps its cached copy until it hears of the change
        assertThat(a.subject()).isEqualTo("v1 Ada");
        // Every event since its baseline: its own draft and publish, and the other replica's reset
        assertThat(a.feed.poll()).isEqualTo(3);
        assertThat(a.subject()).isEqualTo("built-in Ada");
    }

    @Test
    void eventsThatCommitOutOfOrderAreStillDelivered() throws Exception {
        MutableClock clock = new MutableClock();
        JdbcTemplateChangeFeed feed = new JdbcTemplateChangeFeed(jdbc, TABLES, Duration.ofHours(1), null,
                Duration.ofMinutes(1), clock);
        List<String> seen = new ArrayList<>();
        feed.subscribe(key -> seen.add(key.templateId()));
        feed.baseline();
        long start = feed.checkpoint();

        try (Connection slow = dataSource.getConnection()) {
            slow.setAutoCommit(false);
            insertEvent(slow, "SLOW");               // takes the lower id, commits last
            insertCommitted("FAST");                 // takes the higher id, commits first

            assertThat(feed.poll()).isEqualTo(1);
            assertThat(seen).containsExactly("FAST");

            clock.advance(Duration.ofSeconds(30));
            slow.commit();
        }
        assertThat(feed.poll()).as("the late commit is inside the window").isEqualTo(1);
        assertThat(seen).containsExactly("FAST", "SLOW");
        assertThat(feed.poll()).as("nothing is delivered twice").isZero();
        assertThat(feed.checkpoint()).isEqualTo(start);

        clock.advance(Duration.ofMinutes(2));
        assertThat(feed.poll()).isZero();
        assertThat(feed.checkpoint()).isEqualTo(maxEventId());
    }

    @Test
    void anEventStillUncommittedAtStartupIsDelivered() throws Exception {
        List<String> seen = new ArrayList<>();
        JdbcTemplateChangeFeed feed;
        try (Connection open = dataSource.getConnection()) {
            open.setAutoCommit(false);
            insertEvent(open, "OPEN");               // lower id, not committed at startup
            insertCommitted("COMMITTED");            // higher id, visible at startup

            feed = new JdbcTemplateChangeFeed(jdbc, TABLES, Duration.ofHours(1), null, Duration.ofMinutes(1),
                    new MutableClock());
            feed.subscribe(key -> seen.add(key.templateId()));
            feed.baseline();
            assertThat(feed.poll()).as("recent events are delivered once").isEqualTo(1);

            open.commit();
        }
        assertThat(feed.poll()).isEqualTo(1);
        assertThat(seen).containsExactly("COMMITTED", "OPEN");
    }

    @Test
    void missingIdsHoldNothingUp() throws Exception {
        MutableClock clock = new MutableClock();
        JdbcTemplateChangeFeed feed = new JdbcTemplateChangeFeed(jdbc, TABLES, Duration.ofHours(1), null,
                Duration.ofMinutes(1), clock);
        List<String> seen = new ArrayList<>();
        feed.subscribe(key -> seen.add(key.templateId()));
        feed.baseline();

        try (Connection rolledBack = dataSource.getConnection()) {
            rolledBack.setAutoCommit(false);
            insertEvent(rolledBack, "GONE");
            rolledBack.rollback();
        }
        insertCommitted("AFTER");

        assertThat(feed.poll()).isEqualTo(1);
        insertCommitted("NEXT");
        assertThat(feed.poll()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(2));
        assertThat(feed.poll()).isZero();
        assertThat(feed.checkpoint()).isEqualTo(maxEventId());
        assertThat(seen).containsExactly("AFTER", "NEXT");
    }

    private static long maxEventId() {
        return jdbc.sql("SELECT MAX(id) FROM " + TABLES.templateEvent()).query(Long.class).single();
    }

    private static void insertEvent(Connection connection, String templateId) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + TABLES.templateEvent()
                + " (channel, template_id, action, actor, occurred_at) VALUES ('email', ?, 'PUBLISHED', 't', now())")) {
            insert.setString(1, templateId);
            insert.executeUpdate();
        }
    }

    private static void insertCommitted(String templateId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            insertEvent(connection, templateId);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
