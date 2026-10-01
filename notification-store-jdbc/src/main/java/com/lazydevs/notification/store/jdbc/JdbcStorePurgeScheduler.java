package com.lazydevs.notification.store.jdbc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link JdbcStorePurger#purgeAll()} every
 * {@code notification.store.jdbc.purge.interval} on a module-owned
 * single daemon thread, so the host does not need
 * {@code @EnableScheduling}. The first run happens one interval after
 * startup. Started and stopped with the application context.
 */
@Slf4j
public class JdbcStorePurgeScheduler implements SmartLifecycle {

    private final JdbcStorePurger purger;
    private final Duration interval;
    private ScheduledExecutorService executor;

    public JdbcStorePurgeScheduler(JdbcStorePurger purger, Duration interval) {
        this.purger = Objects.requireNonNull(purger, "purger");
        this.interval = JdbcStoreSupport.requirePositive(interval, "notification.store.jdbc.purge.interval");
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "notification-store-jdbc-purge");
            thread.setDaemon(true);
            return thread;
        });
        long millis = interval.toMillis();
        executor.scheduleWithFixedDelay(this::runOnce, millis, millis, TimeUnit.MILLISECONDS);
        log.info("JDBC store purge scheduled every {}", interval);
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

    /** The delay between purge runs. */
    public Duration interval() {
        return interval;
    }

    private void runOnce() {
        // An exception escaping a scheduled task cancels every later run.
        try {
            purger.purgeAll();
        } catch (RuntimeException e) {
            log.warn("Scheduled JDBC store purge failed: {}", e.toString());
        }
    }
}
