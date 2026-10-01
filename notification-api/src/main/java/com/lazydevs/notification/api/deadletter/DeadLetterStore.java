package com.lazydevs.notification.api.deadletter;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
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

    /**
     * Claim up to {@code limit} unclaimed entries of one tenant for
     * processing (typically replay), leasing them for {@code lease}.
     *
     * <p>Contract for the claim / acknowledge / release cycle:
     * <ul>
     *   <li><strong>Claim.</strong> Every returned entry is leased to the
     *       caller until {@code now + lease}. While the lease is live, no
     *       other {@code claim} call returns the same entry. Distributed
     *       implementations MUST make this hold across replicas, so two
     *       pods draining the same tenant never replay one entry twice.</li>
     *   <li><strong>Acknowledge.</strong> After processing an entry
     *       successfully, the caller calls {@link #remove(String, String)};
     *       removal is the acknowledgement.</li>
     *   <li><strong>Release.</strong> After a failed attempt, the caller
     *       calls {@link #release(String, String)} so the entry becomes
     *       claimable again immediately.</li>
     *   <li><strong>Expiry.</strong> An entry that is neither removed nor
     *       released (the caller crashed) becomes claimable again once its
     *       lease elapses.</li>
     * </ul>
     *
     * <p>{@code tenantId} is matched exactly against the entry's tenant;
     * {@code null} matches only entries recorded without a tenant. The
     * order of the returned entries is implementation-defined (the JDBC
     * store returns oldest first). A non-positive {@code limit} yields an
     * empty list.
     *
     * <p>The default falls back to {@link #snapshot()} filtered by tenant
     * and truncated to {@code limit}. It takes <strong>no lease and no
     * lock</strong>, so it is only safe for single-replica, in-process
     * stores; {@code lease} is ignored. Added after the SPI's initial
     * release, so existing implementations compile unchanged.
     *
     * @param tenantId tenant whose entries to claim; {@code null} for
     *                 entries without a tenant
     * @param limit    maximum number of entries to return
     * @param lease    how long the claim is held before the entries become
     *                 claimable again
     * @return the claimed entries, never {@code null}
     */
    default List<DeadLetterEntry> claim(String tenantId, int limit, Duration lease) {
        if (limit <= 0) {
            return List.of();
        }
        return snapshot().orElse(List.of()).stream()
                .filter(e -> Objects.equals(tenantId, e.request().getTenantId()))
                .limit(limit)
                .toList();
    }

    /**
     * Claim one specific entry, identified by tenant and original request
     * id, under the same lease contract as
     * {@link #claim(String, int, Duration)}: while the lease is live no
     * other {@code claim} call (targeted or not) returns it, and the caller
     * acknowledges with {@link #remove(String, String)} or gives it back
     * with {@link #release(String, String)}.
     *
     * <p>Returns {@link Optional#empty()} when the entry does not exist
     * <em>or</em> is currently leased by another claimer; callers that need
     * to tell the two apart follow up with
     * {@link #findByRequestId(String, String)}.
     *
     * <p>The default returns {@link #findByRequestId(String, String)} and,
     * like the default {@link #claim(String, int, Duration)}, takes
     * <strong>no lease</strong>: it is only safe for single-replica stores.
     * A store that overrides {@link #claim(String, int, Duration)} with real
     * leases must override this method too, or single-entry replay bypasses
     * its leases. Added after the SPI's initial release, so existing
     * implementations compile unchanged.
     *
     * @param tenantId  tenant of the entry; {@code null} for entries
     *                  without a tenant
     * @param requestId original request id of the entry
     * @param lease     how long the claim is held before the entry becomes
     *                  claimable again
     * @return the claimed entry, or empty if absent or claimed elsewhere
     */
    default Optional<DeadLetterEntry> claim(String tenantId, String requestId, Duration lease) {
        return findByRequestId(tenantId, requestId);
    }

    /**
     * Give up the lease taken by {@link #claim(String, int, Duration)} on a
     * single entry so it can be claimed again immediately, typically after
     * a failed replay. Releasing an entry that is not claimed, or does not
     * exist, is a no-op. Implementations should never throw.
     *
     * <p>Default is a no-op, matching the lock-free default of
     * {@link #claim(String, int, Duration)}.
     *
     * @param tenantId  tenant of the entry; {@code null} for entries
     *                  without a tenant
     * @param requestId original request id of the entry
     */
    default void release(String tenantId, String requestId) {
        // No-op by default: the default claim takes no lease.
    }
}
