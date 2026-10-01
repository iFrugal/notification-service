package com.lazydevs.notification.redis.autoconfigure;

import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.config.NotificationCoreDefaultsAutoConfiguration;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.store.ConditionalOnStoreType;
import com.lazydevs.notification.core.store.StoreFeature;
import com.lazydevs.notification.core.store.StoreType;
import com.lazydevs.notification.redis.RedisDeadLetterStore;
import com.lazydevs.notification.redis.RedisDeliveryEventStore;
import com.lazydevs.notification.redis.RedisIdempotencyStore;
import com.lazydevs.notification.redis.RedisRateLimiter;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Registers the DD-14 Redis-backed SPI implementations for every enabled
 * feature whose store family resolves to Redis, either through
 * {@code notification.store.type=redis} or an explicit
 * {@code notification.redis.<feature>.enabled=true}
 * (see {@link com.lazydevs.notification.core.store.StoreFeature}).
 *
 * <p>Ordered after Boot's Redis auto-configuration, which supplies the
 * {@link StringRedisTemplate} and {@link LettuceConnectionFactory}, and
 * before {@link NotificationCoreDefaultsAutoConfiguration}, so an enabled
 * Redis store is registered first and the in-memory default backs off.
 * Each bean in turn backs off when the application supplies its own
 * implementation of the SPI.
 */
@AutoConfiguration(afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        before = NotificationCoreDefaultsAutoConfiguration.class)
@ConditionalOnClass({StringRedisTemplate.class, LettuceBasedProxyManager.class})
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationRedisAutoConfiguration {

    @Bean
    @ConditionalOnStoreType(feature = StoreFeature.IDEMPOTENCY, type = StoreType.REDIS)
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public RedisIdempotencyStore redisIdempotencyStore(StringRedisTemplate redis,
                                                       NotificationProperties properties) {
        return new RedisIdempotencyStore(redis, properties);
    }

    @Bean
    @ConditionalOnStoreType(feature = StoreFeature.RATE_LIMIT, type = StoreType.REDIS)
    @ConditionalOnMissingBean(RateLimiter.class)
    public RedisRateLimiter redisRateLimiter(NotificationProperties properties,
                                             LettuceConnectionFactory connectionFactory) {
        return new RedisRateLimiter(properties, connectionFactory);
    }

    @Bean
    @ConditionalOnStoreType(feature = StoreFeature.DEAD_LETTER, type = StoreType.REDIS)
    @ConditionalOnMissingBean(DeadLetterStore.class)
    public RedisDeadLetterStore redisDeadLetterStore(StringRedisTemplate redis,
                                                     NotificationProperties properties) {
        return new RedisDeadLetterStore(redis, properties);
    }

    @Bean
    @ConditionalOnStoreType(feature = StoreFeature.DELIVERY_EVENTS, type = StoreType.REDIS)
    @ConditionalOnMissingBean(DeliveryEventStore.class)
    public RedisDeliveryEventStore redisDeliveryEventStore(StringRedisTemplate redis,
                                                           NotificationProperties properties) {
        return new RedisDeliveryEventStore(redis, properties);
    }
}
