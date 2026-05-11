package com.lazydevs.notification.core.delivery;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.core.config.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default in-memory {@link DeliveryEventStore} (DD-17).
 *
 * <p>Backed by a Caffeine bounded LRU keyed by an internal monotonic
 * sequence number. Insertion order is reconstructed at snapshot time
 * (most-recent-first) using the sequence — Caffeine's underlying
 * ConcurrentHashMap doesn't guarantee iteration order.
 *
 * <p>Bean is opt-in via {@code @ConditionalOnProperty} — operators
 * who want a custom {@link DeliveryEventStore} register their own bean
 * and leave {@code notification.delivery-events.enabled=false}.
 * Same opt-in discipline {@code RedisIdempotencyStore} uses (DD-14
 * §"Bean is opt-in").
 *
 * <p>Same shape as {@code InMemoryDeadLetterStore} — the two SPIs are
 * close cousins (DD-17 §"Why not merge with the DLQ store").
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "notification.delivery-events", name = "enabled", havingValue = "true")
public class InMemoryDeliveryEventStore implements DeliveryEventStore {

    private final Cache<Long, DeliveryEvent> events;
    private final AtomicLong sequence = new AtomicLong();

    public InMemoryDeliveryEventStore(NotificationProperties properties) {
        long maxEntries = properties.getDeliveryEvents().getMaxEntries();
        this.events = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .build();
        log.info("InMemoryDeliveryEventStore initialized: maxEntries={}", maxEntries);
    }

    @Override
    public void add(DeliveryEvent event) {
        // SPI contract: never throw. A flaky store mustn't make the
        // webhook handler return 5xx (provider would retry forever).
        try {
            events.put(sequence.incrementAndGet(), event);
        } catch (RuntimeException e) {
            log.warn("Failed to record delivery event; swallowing: {}", e.toString());
        }
    }

    @Override
    public Optional<List<DeliveryEvent>> snapshot() {
        // Sort by insertion-id descending — most recent first.
        List<DeliveryEvent> ordered = new ArrayList<>(
                events.asMap().entrySet().stream()
                        .sorted(Comparator.<java.util.Map.Entry<Long, DeliveryEvent>>comparingLong(
                                java.util.Map.Entry::getKey).reversed())
                        .map(java.util.Map.Entry::getValue)
                        .toList());
        return Optional.of(List.copyOf(ordered));
    }

    @Override
    public Optional<List<DeliveryEvent>> findByProviderMessageId(
            String providerName, String providerMessageId) {
        if (providerName == null || providerMessageId == null) {
            return Optional.of(List.of());
        }
        // Linear scan over the bounded map. Same trade-off
        // findByRequestId on the DLQ makes — the buffer is capped, this
        // is operator-driven lookup not a hot path, and a secondary
        // index would double write cost for no measurable replay win.
        List<DeliveryEvent> matches = events.asMap().entrySet().stream()
                .sorted(Comparator.<java.util.Map.Entry<Long, DeliveryEvent>>comparingLong(
                        java.util.Map.Entry::getKey).reversed())
                .map(java.util.Map.Entry::getValue)
                .filter(e -> providerName.equals(e.providerName())
                        && providerMessageId.equals(e.providerMessageId()))
                .toList();
        return Optional.of(List.copyOf(matches));
    }

    @Override
    public int size() {
        return Math.toIntExact(events.estimatedSize());
    }

    /**
     * Force Caffeine's pending size-based evictions to run synchronously.
     * Tests that need deterministic post-bound state call this; same
     * helper {@code InMemoryDeadLetterStore} exposes for the same
     * reason.
     */
    void evictPending() {
        events.cleanUp();
    }
}
