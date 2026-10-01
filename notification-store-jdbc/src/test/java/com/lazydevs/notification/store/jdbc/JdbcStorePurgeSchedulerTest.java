package com.lazydevs.notification.store.jdbc;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class JdbcStorePurgeSchedulerTest {

    @Test
    void runsRepeatedlyAndSurvivesAFailingRun() {
        AtomicInteger calls = new AtomicInteger();
        JdbcPurgeableStore flaky = batchSize -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("database briefly unavailable");
            }
            return 0;
        };
        JdbcStorePurgeScheduler scheduler =
                new JdbcStorePurgeScheduler(new JdbcStorePurger(List.of(flaky), 10), Duration.ofMillis(20));

        scheduler.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> calls.get() >= 3);
            assertThat(scheduler.isRunning()).isTrue();
        } finally {
            scheduler.stop();
        }
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void purgerLoopsUntilABatchComesBackShort() {
        AtomicInteger remaining = new AtomicInteger(25);
        JdbcPurgeableStore store = batchSize -> {
            int deleted = Math.min(batchSize, remaining.get());
            remaining.addAndGet(-deleted);
            return deleted;
        };

        assertThat(new JdbcStorePurger(List.of(store), 10).purgeAll()).isEqualTo(25);
        assertThat(remaining).hasValue(0);
    }

    @Test
    void rejectsNonPositiveSettings() {
        JdbcStorePurger purger = new JdbcStorePurger(List.of(), 1);
        assertThatThrownBy(() -> new JdbcStorePurgeScheduler(purger, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("purge.interval");
        assertThatThrownBy(() -> new JdbcStorePurger(List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("purge.batch-size");
    }
}
