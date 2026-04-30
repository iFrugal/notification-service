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
     *
     * <p>Named {@code add} rather than {@code record} because
     * {@code record} is a Java contextual keyword — using it as a
     * method name reads strangely and trips static analyzers like
     * Sonar's "S6213 — restricted identifier" rule.
     */
    void add(DeadLetterEntry entry);

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

    /**
     * Look up a single dead-letter entry by its tenant + original
     * request id. Used by the DD-15 replay endpoint to reconstruct the
     * original {@link com.lazydevs.notification.api.model.NotificationRequest}
     * before re-submitting it.
     *
     * <p>Returns {@link Optional#empty()} when no matching entry exists
     * — including the case where the backend can't answer the lookup
     * cheaply (e.g. a future Redis-backed store would have to scan, but
     * since the DLQ is bounded the scan cost is small enough that we
     * don't bother distinguishing "not found" from "lookup unsupported"
     * yet).
     *
     * <p>{@code tenantId} is part of the key because requestIds are
     * caller-generated and only unique <em>within</em> a tenant; two
     * tenants can both submit "req-001". Cross-tenant collision would
     * silently leak one tenant's payload to another's replay otherwise.
     *
     * <p>Default is {@code Optional.empty()} so existing impls compile
     * unchanged (DD-15 was added after the SPI's initial release).
     */
    default Optional<DeadLetterEntry> findByRequestId(String tenantId, String requestId) {
        return Optional.empty();
    }

    /**
     * Remove a single dead-letter entry by its tenant + original
     * request id. Used by the DD-15 replay endpoint after a successful
     * replay to keep the DLQ as "the things still broken".
     *
     * <p>Returns {@code true} only if an entry was actually removed —
     * idempotent against repeated calls. Implementations should never
     * throw; like {@link #add}, a flaky removal must not cascade into
     * a caller-visible error.
     *
     * <p>Default is {@code false} (no-op) so existing impls compile
     * unchanged.
     */
    default boolean remove(String tenantId, String requestId) {
        return false;
    }
}
