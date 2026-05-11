package com.lazydevs.notification.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.core.config.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Redis-backed {@link DeliveryEventStore} (DD-17). Mirrors the shape
 * of {@code RedisDeadLetterStore} (DD-14) — a single Redis LIST
 * holds JSON-serialised events, capped via {@code LTRIM} on every
 * write.
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code add} — {@code LPUSH} + {@code LTRIM 0 maxEntries-1}</li>
 *   <li>{@code snapshot} — {@code LRANGE 0 maxEntries-1}, parsed back
 *       to records, most-recent-first</li>
 *   <li>{@code findByProviderMessageId} — same {@code LRANGE} then
 *       filter; cheap enough at the bounded cap</li>
 *   <li>{@code size} — {@code LLEN}</li>
 * </ul>
 *
 * <p>{@code LPUSH + LTRIM} isn't strictly atomic — race window allows
 * brief overshoot under concurrent writes. Acceptable for an
 * operator-inspection surface; not a correctness boundary.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "notification.redis.delivery-events",
        name = "enabled", havingValue = "true")
public class RedisDeliveryEventStore implements DeliveryEventStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String key;
    private final int maxEntries;

    public RedisDeliveryEventStore(StringRedisTemplate redis,
                                   NotificationProperties properties) {
        this.redis = redis;
        this.json = new ObjectMapper().registerModule(new JavaTimeModule());
        String prefix = properties.getRedis().getKeyPrefix();
        this.key = prefix + ":delivery-events";
        this.maxEntries = properties.getRedis().getDeliveryEvents().getMaxEntries();
        log.info("RedisDeliveryEventStore initialized: key={}, maxEntries={}", key, maxEntries);
    }

    @Override
    public void add(DeliveryEvent event) {
        // SPI contract: never throw — DD-17 §SPI. A flaky DLQ-style
        // store mustn't turn a webhook 200 into a 5xx.
        try {
            String payload = json.writeValueAsString(event);
            redis.opsForList().leftPush(key, payload);
            redis.opsForList().trim(key, 0, maxEntries - 1L);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise DeliveryEvent; swallowing: {}", e.toString());
        } catch (RuntimeException e) {
            log.warn("Failed to record delivery event to Redis; swallowing: {}", e.toString());
        }
    }

    @Override
    public Optional<List<DeliveryEvent>> snapshot() {
        List<String> raws = redis.opsForList().range(key, 0, maxEntries - 1L);
        if (raws == null) {
            return Optional.of(List.of());
        }
        List<DeliveryEvent> out = new ArrayList<>(raws.size());
        for (String raw : raws) {
            try {
                out.add(json.readValue(raw, DeliveryEvent.class));
            } catch (JsonProcessingException e) {
                log.warn("Skipping malformed DeliveryEvent entry: {}", e.getMessage());
            }
        }
        return Optional.of(List.copyOf(out));
    }

    @Override
    public Optional<List<DeliveryEvent>> findByProviderMessageId(
            String providerName, String providerMessageId) {
        if (providerName == null || providerMessageId == null) {
            return Optional.of(List.of());
        }
        // Scan the bounded list — same trade-off RedisDeadLetterStore
        // takes on findByRequestId. The buffer is capped, lookup is
        // operator-driven, secondary indices double the write cost for
        // no real win.
        List<String> raws = redis.opsForList().range(key, 0, maxEntries - 1L);
        if (raws == null) {
            return Optional.of(List.of());
        }
        List<DeliveryEvent> matches = new ArrayList<>();
        for (String raw : raws) {
            try {
                DeliveryEvent candidate = json.readValue(raw, DeliveryEvent.class);
                if (providerName.equals(candidate.providerName())
                        && providerMessageId.equals(candidate.providerMessageId())) {
                    matches.add(candidate);
                }
            } catch (JsonProcessingException e) {
                log.warn("Skipping malformed DeliveryEvent during findByProviderMessageId: {}",
                        e.getMessage());
            }
        }
        return Optional.of(List.copyOf(matches));
    }

    @Override
    public int size() {
        Long len = redis.opsForList().size(key);
        return len == null ? 0 : len.intValue();
    }
}
