package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.template.TemplateChangeFeed;
import com.lazydevs.notification.api.template.TemplateChangeListener;
import com.lazydevs.notification.api.template.TemplateKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@link TemplateChangeFeed} that polls the template event table (DD-38): every
 * {@code notification.template.store.poll-interval} it reads the events after the last one
 * it has seen and tells its listeners which keys changed. It holds no connection between
 * polls and needs nothing but the store's own tables.
 *
 * <p>Event ids come from an identity column, which hands out ids when rows are inserted, not
 * when transactions commit, so an id can become visible after a higher one. The feed
 * therefore keeps reading the events it delivered within the late-commit window (a minute,
 * or four poll intervals if longer) on every poll, skipping the ones already delivered, and
 * only moves its checkpoint past an id once that id has been delivered for longer than the
 * window. An event whose transaction commits more than a window after a higher id was seen
 * is missed; the store's write transactions take milliseconds. Ids that never appear
 * (rolled-back inserts, deleted rows) hold nothing up.
 *
 * <p>Started at the end of the context refresh, from the highest event id older than the
 * late-commit window; runs on one daemon thread. Under row-level security a session sees only some events: set
 * {@code notification.store.jdbc.template.change-function} to a {@code SECURITY DEFINER}
 * function that returns them all.
 */
@Slf4j
public class JdbcTemplateChangeFeed implements TemplateChangeFeed, SmartLifecycle {

    /** Most events read per query. */
    static final int BATCH = 500;

    private final JdbcClient jdbc;
    private final Duration interval;
    private final Duration lateCommitWindow;
    private final Clock clock;
    private final String maxIdSql;
    private final String changesSql;
    private final List<TemplateChangeListener> listeners = new CopyOnWriteArrayList<>();

    /** Events are read after this id; every id at or below it was delivered or is past the window. */
    private long checkpoint;
    private boolean baselined;
    /** Ids above the checkpoint already delivered, with when. */
    private final Map<Long, Instant> recent = new HashMap<>();
    private boolean failing;

    private ScheduledExecutorService executor;

    /**
     * @param changeFunction optional {@code SECURITY DEFINER} function {@code (p_after, p_limit)};
     *                       {@code null} or blank reads the table
     */
    public JdbcTemplateChangeFeed(JdbcClient jdbc, JdbcStoreTables tables, Duration interval, String changeFunction) {
        this(jdbc, tables, interval, changeFunction, defaultLateCommitWindow(interval), Clock.systemUTC());
    }

    JdbcTemplateChangeFeed(JdbcClient jdbc, JdbcStoreTables tables, Duration interval, String changeFunction,
                           Duration lateCommitWindow, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.interval = JdbcStoreSupport.requirePositive(interval, "notification.template.store.poll-interval");
        this.lateCommitWindow = lateCommitWindow;
        this.clock = clock;
        String function = JdbcTemplateStore.function(changeFunction,
                "notification.store.jdbc.template.change-function");
        this.maxIdSql = "SELECT COALESCE(MAX(id), 0) FROM " + tables.templateEvent()
                + " WHERE occurred_at < now() - (:window * INTERVAL '1 millisecond')";
        this.changesSql = function != null
                ? "SELECT id, tenant_id, channel, template_id, locale FROM " + function + "(:after, :limit) ORDER BY id"
                : "SELECT id, tenant_id, channel, template_id, locale FROM " + tables.templateEvent()
                + " WHERE id > :after ORDER BY id LIMIT :limit";
    }

    /** A minute, or four poll intervals if that is longer. */
    static Duration defaultLateCommitWindow(Duration interval) {
        Duration fourPolls = interval.multipliedBy(4);
        return fourPolls.compareTo(Duration.ofMinutes(1)) > 0 ? fourPolls : Duration.ofMinutes(1);
    }

    @Override
    public void subscribe(TemplateChangeListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        baseline();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "notification-template-change-feed");
            thread.setDaemon(true);
            return thread;
        });
        long millis = interval.toMillis();
        executor.scheduleWithFixedDelay(this::runOnce, millis, millis, TimeUnit.MILLISECONDS);
        log.info("Template change feed polling every {}", interval);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    /** The delay between polls. */
    public Duration interval() {
        return interval;
    }

    /**
     * Start below the events of the last late-commit window: whatever happened before is not
     * cached in this new process, while an event of a transaction still open at startup keeps
     * an id above the checkpoint and is delivered when it commits. When the id cannot be read,
     * the first successful poll delivers every visible event once instead.
     */
    synchronized void baseline() {
        if (baselined) {
            return;
        }
        try {
            Long max = jdbc.sql(maxIdSql).param("window", lateCommitWindow.toMillis()).query(Long.class).single();
            checkpoint = max == null ? 0 : max;
        } catch (RuntimeException e) {
            log.warn("Template change feed could not read the latest event id; starting from the first: {}",
                    e.toString());
            checkpoint = 0;
        }
        baselined = true;
    }

    private void runOnce() {
        // An exception escaping a scheduled task cancels every later run.
        try {
            poll();
            if (failing) {
                log.info("Template change feed recovered");
                failing = false;
            }
        } catch (RuntimeException e) {
            if (!failing) {
                log.warn("Template change feed poll failed; retrying every {}: {}", interval, e.toString());
                failing = true;
            }
        }
    }

    /**
     * Read the events after the checkpoint, deliver the ones not delivered yet, and move the
     * checkpoint past the ids delivered longer ago than the late-commit window.
     *
     * @return the number of changes delivered
     */
    synchronized int poll() {
        baseline();
        Instant now = clock.instant();
        int count = 0;
        long after = checkpoint;
        while (true) {
            List<Change> changes = jdbc.sql(changesSql)
                    .param("after", after)
                    .param("limit", BATCH)
                    .query((rs, n) -> new Change(rs.getLong("id"), rs.getString("tenant_id"), rs.getString("channel"),
                            rs.getString("template_id"), rs.getString("locale")))
                    .list();
            for (Change change : changes) {
                if (change.id() > checkpoint && recent.putIfAbsent(change.id(), now) == null) {
                    deliver(change);
                    count++;
                }
            }
            if (changes.size() < BATCH) {
                break;
            }
            after = changes.getLast().id();
        }
        forget(now);
        return count;
    }

    /** Move the checkpoint past the ids delivered longer ago than the late-commit window. */
    private void forget(Instant now) {
        Instant cutoff = now.minus(lateCommitWindow);
        recent.entrySet().removeIf(entry -> {
            if (entry.getValue().isBefore(cutoff)) {
                checkpoint = Math.max(checkpoint, entry.getKey());
                return true;
            }
            return false;
        });
    }

    private void deliver(Change change) {
        TemplateKey key;
        try {
            key = new TemplateKey(change.tenantId(), change.channel(), change.templateId(), change.locale());
        } catch (IllegalArgumentException e) {
            log.warn("Template change feed skipped event {} with an invalid key: {}", change.id(), e.getMessage());
            return;
        }
        for (TemplateChangeListener listener : listeners) {
            try {
                listener.templateChanged(key);
            } catch (RuntimeException e) {
                log.warn("Template change listener {} failed for {}: {}", listener.getClass().getName(), key.name(),
                        e.toString());
            }
        }
    }

    /** The checkpoint; visible for tests. */
    synchronized long checkpoint() {
        return checkpoint;
    }

    private record Change(long id, String tenantId, String channel, String templateId, String locale) {
    }
}
