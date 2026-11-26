package com.lazydevs.notification.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.core.config.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.types.Expiration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Redis-backed implementation of the DD-10 {@link IdempotencyStore}
 * (DD-14). Closes the foreseen-Redis SPI mentioned in DD-10 §SPI.
 *
 * <p>Each {@link IdempotencyKey} maps to a single Redis key holding a
 * JSON-serialised {@link IdempotencyRecord}. The atomic-claim path uses
 * {@code SET NX EX} which Redis guarantees as one operation — no Lua
 * script needed.
 *
 * <p>Bean is opt-in via {@code notification.store.type=redis} (or {@code notification.redis.idempotency.enabled=true})
 * and registered by
 * {@link com.lazydevs.notification.redis.autoconfigure.NotificationRedisAutoConfiguration},
 * which backs off when the application supplies its own {@link IdempotencyStore}.
 */
@Slf4j
public class RedisIdempotencyStore implements IdempotencyStore {

    /**
     * Compare-and-delete for {@link #release}: deletes {@code KEYS[1]} only
     * if the stored record is COMPLETE and belongs to {@code ARGV[1]}. A
     * script runs atomically, so a concurrent re-claim between the read
     * and the delete is impossible. An unparseable value is left alone.
     */
    static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of("""
            local value = redis.call('GET', KEYS[1])
            if not value then
              return 0
            end
            local ok, rec = pcall(cjson.decode, value)
            if not ok or type(rec) ~= 'table' then
              return 0
            end
            if rec['status'] == 'COMPLETE' and rec['notificationId'] == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    /**
     * Compare-and-set for {@link #settleInProgress}: replaces {@code KEYS[1]}
     * with {@code ARGV[2]} (TTL {@code ARGV[3]} seconds) only if the stored
     * record is IN_PROGRESS and belongs to {@code ARGV[1]}. Atomic, like
     * {@link #RELEASE_SCRIPT}.
     */
    static final RedisScript<Long> SETTLE_SCRIPT = RedisScript.of("""
            local value = redis.call('GET', KEYS[1])
            if not value then
              return 0
            end
            local ok, rec = pcall(cjson.decode, value)
            if not ok or type(rec) ~= 'table' then
              return 0
            end
            if rec['status'] == 'IN_PROGRESS' and rec['notificationId'] == ARGV[1] then
              redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String keyPrefix;
    private final long ttlSeconds;

    public RedisIdempotencyStore(StringRedisTemplate redis,
                                 NotificationProperties properties) {
        this.redis = redis;
        this.json = RedisStoreJson.create();
        this.keyPrefix = properties.getRedis().getKeyPrefix();
        // Reuse the in-memory store's TTL config — same operator-facing
        // surface, just a different backing technology.
        this.ttlSeconds = properties.getIdempotency().getTtl().toSeconds();
        log.info("RedisIdempotencyStore initialized: keyPrefix={}, ttl={}s",
                keyPrefix, ttlSeconds);
    }

    @Override
    public Optional<IdempotencyRecord> findExisting(IdempotencyKey key) {
        String value = redis.opsForValue().get(redisKey(key));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(value, IdempotencyRecord.class));
        } catch (JsonProcessingException e) {
            // A malformed entry shouldn't break the request flow — log
            // and act as if the key wasn't there. The next markInProgress
            // call will overwrite it cleanly via SET NX (the prior value
            // gets evicted on TTL anyway).
            log.warn("Malformed IdempotencyRecord at {}: {}", redisKey(key), e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean markInProgress(IdempotencyKey key, String notificationId) {
        return markInProgress(key, notificationId, null);
    }

    @Override
    public boolean markInProgress(IdempotencyKey key, String notificationId, String fingerprint) {
        IdempotencyRecord rec = new IdempotencyRecord(
                notificationId, IdempotencyStatus.IN_PROGRESS, null, java.time.Instant.now(), fingerprint);
        byte[] payload;
        try {
            payload = json.writeValueAsBytes(rec);
        } catch (JsonProcessingException e) {
            // Serialisation failure is a programming error, not a runtime
            // condition the caller should handle. Re-throw.
            throw new IllegalStateException("Failed to serialise IdempotencyRecord", e);
        }
        // SET NX EX is the idiomatic atomic-claim primitive. Using the
        // raw connection because StringRedisTemplate's setIfAbsent +
        // expire is two round-trips; the SET NX EX option is one.
        Boolean acquired = redis.execute((org.springframework.data.redis.core.RedisCallback<Boolean>) connection ->
                connection.stringCommands().set(
                        redisKey(key).getBytes(StandardCharsets.UTF_8),
                        payload,
                        Expiration.seconds(ttlSeconds),
                        SetOption.SET_IF_ABSENT));
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void markComplete(IdempotencyKey key, NotificationResponse response) {
        // Preserve the original notificationId from the IN_PROGRESS
        // record — for replay scenarios DD-10 §"Idempotency replay"
        // requires the cached response to point back at the first
        // requestId, not whatever the new caller sent.
        // The fingerprint (DD-32) is kept the same way.
        IdempotencyRecord existing = findExisting(key).orElse(null);
        String notificationId = existing != null ? existing.notificationId()
                : response.requestId();
        IdempotencyRecord rec = new IdempotencyRecord(
                notificationId, IdempotencyStatus.COMPLETE, IdempotencyStore.storedForm(response),
                java.time.Instant.now(), existing != null ? existing.fingerprint() : null);
        redis.opsForValue().set(redisKey(key), serialise(rec), java.time.Duration.ofSeconds(ttlSeconds));
    }

    /**
     * Reads the in-progress record for its fingerprint, then swaps in the
     * settled record with {@link #SETTLE_SCRIPT}. The fingerprint never
     * changes while the record belongs to one notification id, so the
     * script's check on status and id is enough.
     */
    @Override
    public boolean settleInProgress(IdempotencyKey key, String notificationId, NotificationResponse response) {
        IdempotencyRecord current = findExisting(key).orElse(null);
        if (notificationId == null || current == null || current.status() != IdempotencyStatus.IN_PROGRESS
                || !notificationId.equals(current.notificationId())) {
            return false;
        }
        IdempotencyRecord settled = new IdempotencyRecord(notificationId, IdempotencyStatus.COMPLETE,
                IdempotencyStore.storedForm(response), java.time.Instant.now(), current.fingerprint());
        Long swapped = redis.execute(SETTLE_SCRIPT, List.of(redisKey(key)), notificationId, serialise(settled),
                String.valueOf(ttlSeconds));
        return swapped != null && swapped == 1L;
    }

    private String serialise(IdempotencyRecord rec) {
        try {
            return json.writeValueAsString(rec);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise IdempotencyRecord", e);
        }
    }

    @Override
    public boolean release(IdempotencyKey key, String notificationId) {
        if (notificationId == null) {
            return false;
        }
        Long deleted = redis.execute(RELEASE_SCRIPT, List.of(redisKey(key)), notificationId);
        return deleted != null && deleted == 1L;
    }

    @Override
    public void evictExpired() {
        // No-op — Redis's EX argument handles TTL natively.
    }

    /**
     * Build the Redis key for an {@link IdempotencyKey}. Format:
     * {@code <prefix>:idempotency:<tenant>:<caller>:<key>}. callerId is
     * inserted as the literal string {@code "anonymous"} when null,
     * matching the DD-10 dedup-tuple semantics.
     */
    String redisKey(IdempotencyKey key) {
        String caller = key.callerId() != null ? key.callerId() : "anonymous";
        return keyPrefix + ":idempotency:"
                + key.tenantId() + ":"
                + caller + ":"
                + key.idempotencyKey();
    }
}
