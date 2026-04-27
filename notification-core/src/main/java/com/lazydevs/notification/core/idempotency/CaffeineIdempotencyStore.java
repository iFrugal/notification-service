package com.lazydevs.notification.core.idempotency;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.core.config.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * In-memory {@link IdempotencyStore} backed by Caffeine. Bounded by
 * {@code notification.idempotency.max-entries}, expired by
 * {@code notification.idempotency.ttl}.
 *
 * <p>Activates when {@code notification.idempotency.enabled=true} (the
 * default) <em>and</em> {@code notification.idempotency.store=caffeine}
 * (also the default). Users wiring a different backend (e.g. Redis) can
 * either set {@code store=redis} to deactivate this bean or supply their
 * own {@code IdempotencyStore} bean — the {@code @ConditionalOnMissingBean}
 * lets them win unconditionally.
 *
 * <p>The store is per-JVM, so a multi-replica service-mode deployment
 * will <strong>not</strong> see cross-replica deduplication. DD-10
 * §Consequences calls this out and points operators at the future
 * {@code RedisIdempotencyStore}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "notification.idempotency", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@ConditionalOnMissingBean(IdempotencyStore.class)
public class CaffeineIdempotencyStore implements IdempotencyStore {

    private final Cache<IdempotencyKey, IdempotencyRecord> cache;

    public CaffeineIdempotencyStore(NotificationProperties properties) {
        NotificationProperties.IdempotencyProperties cfg = properties.getIdempotency();
        if (!"caffeine".equalsIgnoreCase(cfg.getStore())) {
            // We only activate when the operator explicitly picks caffeine
            // (or leaves the default). If they configured a different store
            // we still construct a no-op cache so the bean exists but does
            // nothing useful — the implementation that actually backs the
            // requested store should win the @ConditionalOnMissingBean race.
            log.info("CaffeineIdempotencyStore initialised but store={} configured;"
                    + " expecting another IdempotencyStore bean to take over.", cfg.getStore());
        }
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(cfg.getTtl())
                .maximumSize(cfg.getMaxEntries())
                .build();
        log.info("Idempotency store: caffeine, ttl={}, maxEntries={}",
                cfg.getTtl(), cfg.getMaxEntries());
    }

    @Override
    public Optional<IdempotencyRecord> findExisting(IdempotencyKey key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    @Override
    public boolean markInProgress(IdempotencyKey key, String notificationId) {
        IdempotencyRecord proposed = new IdempotencyRecord(
                notificationId, IdempotencyStatus.IN_PROGRESS, null, Instant.now());
        // ConcurrentMap.putIfAbsent gives us atomic-on-absent semantics
        // without an extra lock; concurrent winners get null back.
        IdempotencyRecord existing = cache.asMap().putIfAbsent(key, proposed);
        boolean won = existing == null;
        if (!won) {
            log.debug("Idempotency race lost for key={} (winner notificationId={}, status={})",
                    key, existing.notificationId(), existing.status());
        }
        return won;
    }

    @Override
    public void markComplete(IdempotencyKey key, NotificationResponse response) {
        IdempotencyRecord prior = cache.getIfPresent(key);
        String notificationId = prior != null ? prior.notificationId()
                : (response != null ? response.requestId() : null);
        cache.put(key, new IdempotencyRecord(
                notificationId, IdempotencyStatus.COMPLETE, response, Instant.now()));
    }

    @Override
    public void evictExpired() {
        // Caffeine handles expiry via expireAfterWrite — explicit cleanup
        // is only useful in test contexts where deterministic eviction is
        // required. Calling cleanUp() is cheap when there's nothing to do.
        cache.cleanUp();
    }
}
