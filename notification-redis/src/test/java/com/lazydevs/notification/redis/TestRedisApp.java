package com.lazydevs.notification.redis;

import com.lazydevs.notification.core.config.NotificationProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Minimal Spring Boot application for the Redis integration tests.
 *
 * <p>Lives at the package root so {@code @SpringBootApplication}'s
 * implicit {@code @ComponentScan} picks up the
 * {@code RedisIdempotencyStore} / {@code RedisRateLimiter} /
 * {@code RedisDeadLetterStore} beans (they're {@code @Component} in the
 * same package). Each test uses
 * {@code @SpringBootTest(classes = TestRedisApp.class)} — a single
 * configuration class, no inline {@code TestApp} per test.
 *
 * <p>{@link EnableConfigurationProperties} on
 * {@link NotificationProperties} is the bit the inline TestApps in the
 * first push were missing — without it, Spring couldn't autowire the
 * properties bean into the Redis components' constructors, and the
 * conditions on the components silently dropped them as ineligible
 * candidates. The Testcontainers skip on Docker-less sandboxes hid
 * this until CI ran.
 */
@SpringBootApplication
@EnableConfigurationProperties(NotificationProperties.class)
public class TestRedisApp {
}
