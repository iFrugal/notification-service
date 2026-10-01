package com.lazydevs.notification.redis.autoconfigure;

import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.config.NotificationCoreDefaultsAutoConfiguration;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.redis.RedisDeadLetterStore;
import com.lazydevs.notification.redis.RedisDeliveryEventStore;
import com.lazydevs.notification.redis.RedisIdempotencyStore;
import com.lazydevs.notification.redis.RedisRateLimiter;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Registers the DD-14 Redis-backed SPI implementations, each behind its own
 * {@code notification.redis.<feature>.enabled} flag.
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
    @ConditionalOnProperty(prefix = "notification.redis.idempotency", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public RedisIdempotencyStore redisIdempotencyStore(StringRedisTemplate redis,
                                                       NotificationProperties properties) {
        return new RedisIdempotencyStore(redis, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "notification.redis.rate-limit", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(RateLimiter.class)
    public RedisRateLimiter redisRateLimiter(NotificationProperties properties,
                                             LettuceConnectionFactory connectionFactory) {
        return new RedisRateLimiter(properties, connectionFactory);
    }

    @Bean
    @ConditionalOnProperty(prefix = "notification.redis.dead-letter", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(DeadLetterStore.class)
    public RedisDeadLetterStore redisDeadLetterStore(StringRedisTemplate redis,
                                                     NotificationProperties properties) {
        return new RedisDeadLetterStore(redis, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "notification.redis.delivery-events", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(DeliveryEventStore.class)
    public RedisDeliveryEventStore redisDeliveryEventStore(StringRedisTemplate redis,
                                                           NotificationProperties properties) {
        return new RedisDeliveryEventStore(redis, properties);
    }
}
