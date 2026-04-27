package com.lazydevs.notification.api.deadletter;

import java.util.List;
import java.util.Optional;

/**
 * SPI for recording notifications that exhausted their retries or were
 * classified as permanent failures (DD-13).
 *
 * <p>The default implementation is an in-memory bounded LRU
 * (see {@code InMemoryDeadLetterStore}). Future Redis / Kafka / S3
 * backed implementations plug in via Spring's
 * {@code @ConditionalOnMissingBean} — same shape as the DD-10 idempotency
 * SPI and the DD-12 rate-limiter SPI.
 */
public interface DeadLetterStore {

    /**
     * Persist a single dead-letter entry. Implementations should
     * never throw — losing a DLQ entry is regrettable but must not
     * propagate back to the caller as an error. Log instead.
     */
    void record(DeadLetterEntry entry);

    /**
     * Return a snapshot of currently-tracked entries, most recent first,
     * if the backing store can produce one.
     *
     * <p>Returns {@link Optional#empty()} for backends that don't expose
     * iteration cheaply (e.g. a Redis-backed store with
     * 100k entries — the operator should query Redis directly). The
     * in-memory default always returns a present list.
     */
    Optional<List<DeadLetterEntry>> snapshot();

    /**
     * Number of entries currently held. Returns {@code -1} when the
     * backend can't answer cheaply (mirrors {@link #snapshot()}'s
     * empty case).
     */
    int size();
}
