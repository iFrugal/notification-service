package com.lazydevs.notification.redis;

import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitProperties;
import com.lazydevs.notification.core.ratelimit.RateLimitRuleResolver;
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
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

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
 * {@code (tenant)} > by-channel > default) is applied at decision time,
 * through the {@link RateLimitRuleResolver} the in-memory limiter uses, so
 * bands and scope (DD-30) behave identically. The local
 * {@code ConcurrentHashMap} is just a thin handle cache for
 * {@link BucketProxy} instances — actual token state lives in Redis.
 */
@Slf4j
public class RedisRateLimiter implements RateLimiter {

    private final RateLimitRuleResolver resolver;
    private final String keyPrefix;
    private final LettuceConnectionFactory connectionFactory;
    private final ConcurrentHashMap<String, BucketProxy> bucketHandles = new ConcurrentHashMap<>();

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
        RateLimitProperties config = properties.getRateLimit();
        this.keyPrefix = properties.getRedis().getKeyPrefix();
        this.connectionFactory = connectionFactory;

        // Same rule resolution the in-memory limiter uses: one copy of
        // the semantics, two backing stores for the buckets themselves.
        this.resolver = new RateLimitRuleResolver(config);

        log.info("RedisRateLimiter registered (lazy-connect): keyPrefix={}, default={}, {} override(s)",
                keyPrefix, config.getDefaultRule(), resolver.overrideCount());
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
        ConsumptionProbe probe = bucketFor(key).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return Decision.allow();
        }
        // With several bands this is the time until every band has a token.
        Duration retryAfter = Duration.ofNanos(probe.getNanosToWaitForRefill());
        log.debug("Rate limit hit (Redis) — retry in {}ms", retryAfter.toMillis());
        return Decision.deny(retryAfter);
    }

    /**
     * Give one token back to the bucket {@code key} resolves to (DD-30).
     * Bucket4j's {@code addTokens} never fills a band above its capacity;
     * on a bucket Redis no longer holds it recreates the bucket full, which
     * is the state an expired bucket would have anyway.
     */
    @Override
    public void refund(RateLimitKey key) {
        bucketFor(key).addTokens(1);
    }

    /**
     * The {@link BucketProxy} for the bucket {@code key} resolves to. One
     * handle per Redis key; the Redis-side state is keyed by
     * {@link #redisKey(RateLimitKey)}.
     */
    private BucketProxy bucketFor(RateLimitKey key) {
        RateLimitRuleResolver.Resolution resolution = resolver.resolve(key);
        String redisKey = redisKey(key, resolution);
        return bucketHandles.computeIfAbsent(redisKey, k -> proxyManager().builder().build(
                k.getBytes(StandardCharsets.UTF_8),
                () -> RateLimitRuleResolver.bucketConfiguration(resolution.rule())));
    }

    /** Build the Redis key of the bucket this rate-limit key resolves to. */
    String redisKey(RateLimitKey key) {
        return redisKey(key, resolver.resolve(key));
    }

    /**
     * {@code <prefix>:ratelimit:<tenant>:<caller>:<channel>} for the default
     * {@code TENANT_CALLER} scope (the 1.2.0 key), and
     * {@code <prefix>:ratelimit-shared:<ruleId>:<channel>} for a
     * {@code CHANNEL}-scoped rule. The different segment keeps a shared
     * bucket from colliding with any tenant's bucket.
     */
    private String redisKey(RateLimitKey key, RateLimitRuleResolver.Resolution resolution) {
        if (resolution.channelScoped()) {
            return keyPrefix + ":ratelimit-shared:" + resolution.ruleId() + ":"
                    + key.channel().toLowerCase(Locale.ROOT);
        }
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
