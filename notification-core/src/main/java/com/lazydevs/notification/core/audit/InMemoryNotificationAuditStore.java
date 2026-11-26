package com.lazydevs.notification.core.audit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lazydevs.notification.api.audit.NotificationAuditStore;
import com.lazydevs.notification.api.model.NotificationAudit;
import com.lazydevs.notification.api.model.NotificationResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * In-process {@link NotificationAuditStore} (DD-33), the default for
 * {@code notification.audit.enabled=true} with
 * {@code notification.store.type=memory}.
 *
 * <p>Bounded by {@code notification.audit.max-entries} (least recently
 * written records go first) and expired after
 * {@code notification.audit.retention-days}. Per process: in a deployment
 * with several instances each sees only its own sends, so use the JDBC
 * store there. Lookups by idempotency key and the recent listing scan the
 * cache, which is fine at the default bound of 10,000 records.
 *
 * <p>Records are copied on the way in and out, so callers cannot change a
 * stored record.
 */
public class InMemoryNotificationAuditStore implements NotificationAuditStore {

    private static final Comparator<NotificationAudit> BY_RECEIVED_AT = Comparator.comparing(
            NotificationAudit::getReceivedAt, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final Cache<Key, NotificationAudit> cache;
    private final Clock clock;

    public InMemoryNotificationAuditStore(long maxEntries, Duration retention, Clock clock) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(retention)
                .build();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void insert(NotificationAudit audit) {
        cache.put(new Key(audit.getTenantId(), audit.getRequestId()), copy(audit));
    }

    @Override
    public boolean recordOutcome(NotificationResponse outcome) {
        Instant now = clock.instant();
        NotificationAudit updated = cache.asMap().computeIfPresent(new Key(outcome.tenantId(), outcome.requestId()),
                (key, current) -> {
                    NotificationAudit next = copy(current);
                    next.applyOutcome(outcome, now);
                    return next;
                });
        return updated != null;
    }

    @Override
    public Optional<NotificationAudit> find(String tenantId, String requestId) {
        return Optional.ofNullable(cache.getIfPresent(new Key(tenantId, requestId)))
                .map(InMemoryNotificationAuditStore::copy);
    }

    @Override
    public Optional<NotificationAudit> findByIdempotencyKey(String tenantId, String callerId, String idempotencyKey) {
        return cache.asMap().values().stream()
                .filter(a -> Objects.equals(tenantId, a.getTenantId())
                        && Objects.equals(callerId, a.getCallerId())
                        && Objects.equals(idempotencyKey, a.getIdempotencyKey()))
                .max(BY_RECEIVED_AT)
                .map(InMemoryNotificationAuditStore::copy);
    }

    @Override
    public List<NotificationAudit> findRecent(String tenantId, int limit) {
        return cache.asMap().values().stream()
                .filter(a -> Objects.equals(tenantId, a.getTenantId()))
                .sorted(BY_RECEIVED_AT.reversed())
                .limit(Math.max(limit, 0))
                .map(InMemoryNotificationAuditStore::copy)
                .toList();
    }

    /** Number of records held, for tests and diagnostics. */
    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    private static NotificationAudit copy(NotificationAudit audit) {
        return audit.toBuilder()
                .metadata(audit.getMetadata() == null ? null : new LinkedHashMap<>(audit.getMetadata()))
                .build();
    }

    private record Key(String tenantId, String requestId) {
    }
}
