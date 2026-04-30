package com.lazydevs.notification.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.core.config.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Redis-backed implementation of the DD-13 {@link DeadLetterStore}
 * (DD-14). Closes the foreseen-Redis SPI mentioned in DD-13's
 * "Out of scope" §.
 *
 * <p>Uses a single Redis LIST under {@code <prefix>:dlq}:
 * <ul>
 *   <li>{@code add} — {@code LPUSH} the JSON-serialised entry, then
 *       {@code LTRIM} the list to {@code maxEntries} so the bound is
 *       enforced eagerly.</li>
 *   <li>{@code snapshot} — {@code LRANGE 0 limit-1} returns
 *       most-recent-first.</li>
 *   <li>{@code size} — {@code LLEN}.</li>
 * </ul>
 *
 * <p>{@code LPUSH + LTRIM} isn't strictly atomic as a pair, but the
 * race window is tiny (a few microseconds) and the overshoot is
 * bounded — at worst we'll briefly hold {@code maxEntries + N}
 * entries with N concurrent writers, then trim back. For an
 * operator-inspection surface this is fine; not a correctness
 * boundary.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "notification.redis.dead-letter",
        name = "enabled", havingValue = "true")
public class RedisDeadLetterStore implements DeadLetterStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String key;
    private final int maxEntries;

    public RedisDeadLetterStore(StringRedisTemplate redis,
                                NotificationProperties properties) {
        this.redis = redis;
        this.json = new ObjectMapper().registerModule(new JavaTimeModule());
        String prefix = properties.getRedis().getKeyPrefix();
        this.key = prefix + ":dlq";
        this.maxEntries = properties.getRedis().getDeadLetter().getMaxEntries();
        log.info("RedisDeadLetterStore initialized: key={}, maxEntries={}", key, maxEntries);
    }

    @Override
    public void add(DeadLetterEntry entry) {
        // SPI contract: never throw — DLQ failure must not turn an
        // already-failed send into a double-failure for the caller.
        try {
            String payload = json.writeValueAsString(entry);
            redis.opsForList().leftPush(key, payload);
            redis.opsForList().trim(key, 0, maxEntries - 1L);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise DeadLetterEntry; swallowing: {}", e.toString());
        } catch (RuntimeException e) {
            log.warn("Failed to record dead-letter entry to Redis; swallowing: {}", e.toString());
        }
    }

    @Override
    public Optional<List<DeadLetterEntry>> snapshot() {
        // Capped at maxEntries because that's the bound. Returning a
        // larger range would only ever yield empties on the tail.
        List<String> raws = redis.opsForList().range(key, 0, maxEntries - 1L);
        if (raws == null) {
            return Optional.of(List.of());
        }
        List<DeadLetterEntry> out = new ArrayList<>(raws.size());
        for (String raw : raws) {
            try {
                out.add(json.readValue(raw, DeadLetterEntry.class));
            } catch (JsonProcessingException e) {
                log.warn("Skipping malformed DLQ entry: {}", e.getMessage());
            }
        }
        return Optional.of(List.copyOf(out));
    }

    @Override
    public int size() {
        Long len = redis.opsForList().size(key);
        return len == null ? 0 : len.intValue();
    }
}
