package com.lazydevs.notification.store.jdbc;

/**
 * A JDBC store whose rows carry an {@code expires_at} and can be purged
 * in batches. Implemented by all three JDBC stores and driven by
 * {@link JdbcStorePurger}.
 */
public interface JdbcPurgeableStore {

    /**
     * Delete at most {@code batchSize} expired rows in one statement.
     * Rows locked by a concurrent purge (another replica) are skipped,
     * not waited for.
     *
     * @return the number of rows deleted; less than {@code batchSize}
     *         means no expired rows were left at the time of the call
     */
    int purgeExpired(int batchSize);
}
