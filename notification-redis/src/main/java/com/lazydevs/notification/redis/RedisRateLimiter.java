package com.lazydevs.notification.redis;

import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitOverride;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitProperties;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitRule;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Redis-backed implementation of the DD-12 {@link RateLimiter} (DD-14).
 *
 * <p>Closes the foreseen-Redis SPI mentioned in DD-12 §"Why Bucket4j" —
 * uses {@code bucket4j-redis}'s {@link LettuceBasedProxyManager} so the
 * actual token-bucket arithmetic is identical to the in-memory
 * {@code Bucket4jRateLimiter} from {@code notification-core}; only the
 * bucket-state persistence boundary moves.
 *
 * <p>The same override-resolution logic (most-specific-wins:
 * {@code (tenant, caller, channel)} > {@code (tenant, caller)} >
 * {@code (tenant)} > default) is applied at decision time. The local
 * {@code ConcurrentHashMap} is just a thin handle cache for
 * {@link BucketProxy} instances — actual token state lives in Redis.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "notification.redis.rate-limit",
        name = "enabled", havingValue = "true")
public class RedisRateLimiter implements RateLimiter {

    private final RateLimitProperties config;
    private final List<RateLimitOverride> sortedOverrides;
    private final String keyPrefix;
    private final LettuceConnectionFactory connectionFactory;
    private final ConcurrentHashMap<RateLimitKey, BucketProxy> bucketHandles = new ConcurrentHashMap<>();

    /**
     * Lazily-initialised Lettuce client + connection + ProxyManager.
     *
     * <p>Originally these were constructor-initialised, but
     * {@code RedisClient.connect()} is <strong>eager</strong> — it
     * actually opens a TCP connection synchronously. That made the
     * bean fail to instantiate during Spring context refresh on any
     * environment where Redis wasn't reachable at the exact instant
     * of bean creation: Testcontainers ramp-up windows, network
     * blips during pod start, even local builds without a Redis
     * running.
     *
     * <p>{@link RedisIdempotencyStore} and {@link RedisDeadLetterStore}
     * don't have this problem because they use Spring Data Redis's
     * {@code StringRedisTemplate} which itself lazy-connects. Bucket4j
     * needs a Lettuce-native connection (Spring Data's
     * {@code RedisConnection} doesn't satisfy the
     * {@code LettuceBasedProxyManager} contract), so we have to manage
     * our own Lettuce client — but we can defer opening it until the
     * first {@link #tryConsume} call.
     */
    private volatile RedisClient redisClient;
    private volatile StatefulRedisConnection<byte[], byte[]> connection;
    private volatile ProxyManager<byte[]> proxyManager;

    public RedisRateLimiter(NotificationProperties properties,
                            LettuceConnectionFactory connectionFactory) {
        this.config = properties.getRateLimit();
        this.keyPrefix = properties.getRedis().getKeyPrefix();
        this.connectionFactory = connectionFactory;

        // Same precedence sort the in-memory limiter uses — keep one
        // copy of the rule resolution semantics, just two backing
        // stores for the buckets themselves.
        this.sortedOverrides = config.getOverrides() == null
                ? List.of()
                : config.getOverrides().stream()
                        .sorted(Comparator.comparingInt(RedisRateLimiter::specificity).reversed())
                        .toList();

        log.info("RedisRateLimiter registered (lazy-connect): keyPrefix={}, default={}, {} override(s)",
                keyPrefix, config.getDefaultRule(), sortedOverrides.size());
    }

    /**
     * Build the Lettuce client + connection + ProxyManager on first
     * use. Double-checked locking guards the field assignment so
     * concurrent first-callers don't each open their own client.
     */
    private ProxyManager<byte[]> proxyManager() {
        ProxyManager<byte[]> p = proxyManager;
        if (p != null) {
            return p;
        }
        synchronized (this) {
            if (proxyManager == null) {
                RedisStandaloneConfiguration sa = (RedisStandaloneConfiguration)
                        connectionFactory.getStandaloneConfiguration();
                RedisURI.Builder uriBuilder = RedisURI.builder()
                        .withHost(sa.getHostName())
                        .withPort(sa.getPort())
                        .withDatabase(sa.getDatabase());
                if (sa.getPassword() != null && sa.getPassword().isPresent()) {
                    uriBuilder.withPassword(sa.getPassword().get());
                }
                this.redisClient = RedisClient.create(uriBuilder.build());
                this.connection = redisClient.connect(ByteArrayCodec.INSTANCE);
                this.proxyManager = LettuceBasedProxyManager.builderFor(connection)
                        .withExpirationStrategy(
                                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                                        Duration.ofHours(1)))
                        .build();
                log.info("RedisRateLimiter Lettuce connection opened on first use");
            }
            return proxyManager;
        }
    }

    @Override
    public Decision tryConsume(RateLimitKey key) {
        BucketProxy bucket = bucketHandles.computeIfAbsent(key, this::buildBucket);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return Decision.allow();
        }
        Duration retryAfter = Duration.ofNanos(probe.getNanosToWaitForRefill());
        log.debug("Rate limit hit (Redis) — retry in {}ms", retryAfter.toMillis());
        return Decision.deny(retryAfter);
    }

    /**
     * Build a {@link BucketProxy} for this key. Called once per unique
     * key; subsequent calls reuse the cached handle. The Redis-side
     * state is keyed by {@link #redisKey(RateLimitKey)}.
     */
    private BucketProxy buildBucket(RateLimitKey key) {
        RateLimitRule rule = ruleFor(key);
        Supplier<BucketConfiguration> configSupplier = () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(rule.getCapacity())
                        .refillGreedy(rule.getRefillTokens(), rule.getRefillPeriod())
                        .build())
                .build();
        return proxyManager().builder().build(
                redisKey(key).getBytes(StandardCharsets.UTF_8),
                configSupplier);
    }

    /**
     * Walk overrides most-specific-first, fall back to default — same
     * algorithm as the in-memory {@code Bucket4jRateLimiter}.
     */
    private RateLimitRule ruleFor(RateLimitKey key) {
        for (RateLimitOverride o : sortedOverrides) {
            if (matches(o, key)) {
                return o;
            }
        }
        return config.getDefaultRule();
    }

    private static boolean matches(RateLimitOverride o, RateLimitKey key) {
        if (!key.tenantId().equals(o.getTenant())) {
            return false;
        }
        if (o.getCaller() != null && !o.getCaller().equals(key.callerId())) {
            return false;
        }
        if (o.getChannel() != null && !o.getChannel().equalsIgnoreCase(key.channel())) {
            return false;
        }
        return true;
    }

    private static int specificity(RateLimitOverride o) {
        int s = 1;
        if (o.getCaller() != null) s++;
        if (o.getChannel() != null) s++;
        return s;
    }

    /** Build the Redis key for this rate-limit scope. */
    String redisKey(RateLimitKey key) {
        return keyPrefix + ":ratelimit:"
                + key.tenantId() + ":"
                + key.callerId() + ":"
                + key.channel();
    }

    /**
     * Close the dedicated Lettuce connection on context shutdown. Spring
     * autocalls this; tests that drop the bean need to invoke it
     * manually if they care about clean Redis tear-down.
     */
    @PreDestroy
    public void shutdown() {
        // Lazy init means these may still be null if the bean was
        // never used — skip cleanly in that case.
        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException e) {
                log.debug("Lettuce connection close failed: {}", e.toString());
            }
        }
        if (redisClient != null) {
            try {
                redisClient.shutdown();
            } catch (RuntimeException e) {
                log.debug("Lettuce client shutdown failed: {}", e.toString());
            }
        }
    }
}
