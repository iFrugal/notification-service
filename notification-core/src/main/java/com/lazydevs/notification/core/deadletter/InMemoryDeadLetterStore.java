package com.lazydevs.notification.core.deadletter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.core.config.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default in-memory {@link DeadLetterStore} (DD-13).
 *
 * <p>Backed by a Caffeine bounded LRU cache — older entries fall off
 * when the {@code max-entries} bound is exceeded. The DLQ is for
 * <em>operator inspection</em>, not for production state-of-the-world,
 * so the bound is intentionally modest (default 1 000) and there's no
 * TTL — entries stay until evicted by size pressure.
 *
 * <p>Insertion order is reconstructed at snapshot time using a
 * monotonic counter on each entry, since Caffeine doesn't guarantee
 * iteration order on its underlying ConcurrentHashMap.
 *
 * <p>Bean is registered only when
 * {@code notification.dead-letter.enabled=true}; a future Redis-backed
 * bean replaces it via {@link ConditionalOnMissingBean}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "notification.dead-letter", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean(DeadLetterStore.class)
public class InMemoryDeadLetterStore implements DeadLetterStore {

    private final Cache<Long, DeadLetterEntry> entries;
    private final AtomicLong sequence = new AtomicLong();

    public InMemoryDeadLetterStore(NotificationProperties properties) {
        long maxEntries = properties.getDeadLetter().getMaxEntries();
        this.entries = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .build();
        log.info("InMemoryDeadLetterStore initialized: maxEntries={}", maxEntries);
    }

    @Override
    public void add(DeadLetterEntry entry) {
        // SPI contract: never throw. Catch + log so a flaky DLQ doesn't
        // turn an already-failed send into a double-failure for the caller.
        try {
            entries.put(sequence.incrementAndGet(), entry);
        } catch (RuntimeException e) {
            log.warn("Failed to record dead-letter entry; swallowing: {}", e.toString());
        }
    }

    @Override
    public Optional<List<DeadLetterEntry>> snapshot() {
        // Iterate the live map, sort by insertion id descending (most
        // recent first), and return an immutable snapshot.
        List<DeadLetterEntry> ordered = new ArrayList<>(
                entries.asMap().entrySet().stream()
                        .sorted(Comparator.<java.util.Map.Entry<Long, DeadLetterEntry>>comparingLong(
                                java.util.Map.Entry::getKey).reversed())
                        .map(java.util.Map.Entry::getValue)
                        .toList());
        return Optional.of(List.copyOf(ordered));
    }

    @Override
    public int size() {
        return Math.toIntExact(entries.estimatedSize());
    }

    @Override
    public Optional<DeadLetterEntry> findByRequestId(String tenantId, String requestId) {
        if (tenantId == null || requestId == null) {
            return Optional.empty();
        }
        // Linear scan over the bounded map. With max-entries default
        // 1000 and replay being operator-driven (i.e. RPS << 1), this
        // is not a hot path. Keeping a secondary index would just
        // double the memory + sync cost for no real win.
        return entries.asMap().values().stream()
                .filter(e -> tenantId.equals(e.request().getTenantId())
                        && requestId.equals(e.request().getRequestId()))
                .findFirst();
    }

    @Override
    public boolean remove(String tenantId, String requestId) {
        if (tenantId == null || requestId == null) {
            return false;
        }
        // Find the storage key (sequence id) whose value matches —
        // the public requestId isn't the cache key, so we have to
        // walk the entries. Same scan cost as findByRequestId, see
        // its rationale.
        try {
            Long key = entries.asMap().entrySet().stream()
                    .filter(en -> tenantId.equals(en.getValue().request().getTenantId())
                            && requestId.equals(en.getValue().request().getRequestId()))
                    .map(java.util.Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            if (key == null) {
                return false;
            }
            entries.invalidate(key);
            return true;
        } catch (RuntimeException e) {
            // SPI contract: never throw. A flaky removal mustn't
            // cascade into the replay endpoint returning 500 when the
            // replay itself succeeded.
            log.warn("Failed to remove dead-letter entry [tenant={}, requestId={}]: {}",
                    tenantId, requestId, e.toString());
            return false;
        }
    }

    /**
     * Force Caffeine's pending size-based evictions to run synchronously.
     * Used by tests that need deterministic post-bound state. Production
     * code never needs this — Caffeine's lazy eviction is fine when the
     * heap-bound matters more than exact synchronous trimming.
     */
    void evictPending() {
        entries.cleanUp();
    }
}
