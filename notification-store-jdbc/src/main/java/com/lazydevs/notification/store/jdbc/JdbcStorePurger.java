package com.lazydevs.notification.store.jdbc;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Deletes expired rows from every registered {@link JdbcPurgeableStore},
 * batch by batch, until none are left.
 *
 * <p>Called by the optional scheduled purge
 * ({@code notification.store.jdbc.purge.enabled=true}); hosts that run
 * their own scheduler call {@link #purgeAll()} directly. Safe to run on
 * every replica at once: batches use {@code FOR UPDATE SKIP LOCKED}, so
 * concurrent purgers split the work instead of blocking each other.
 *
 * <p>Under row-level security the purge sees only the rows the
 * connection's policy exposes; run it with a role (or tenant setting)
 * that can see the rows to purge.
 */
@Slf4j
public class JdbcStorePurger {

    private final List<JdbcPurgeableStore> stores;
    private final int batchSize;

    public JdbcStorePurger(List<? extends JdbcPurgeableStore> stores, int batchSize) {
        this.stores = List.copyOf(stores);
        this.batchSize = JdbcStoreSupport.requirePositive(batchSize, "notification.store.jdbc.purge.batch-size");
    }

    /**
     * Purge every store. A failing store is logged and skipped so the
     * others still get purged.
     *
     * @return total rows deleted
     */
    public int purgeAll() {
        int total = 0;
        for (JdbcPurgeableStore store : stores) {
            try {
                int deleted;
                do {
                    deleted = store.purgeExpired(batchSize);
                    total += deleted;
                } while (deleted >= batchSize);
            } catch (RuntimeException e) {
                log.warn("Purge of {} failed; continuing with the next store: {}",
                        store.getClass().getSimpleName(), e.toString());
            }
        }
        if (total > 0) {
            log.debug("Purged {} expired notification store rows", total);
        }
        return total;
    }

    /** The stores this purger covers, in purge order. */
    public List<JdbcPurgeableStore> stores() {
        return stores;
    }
}
