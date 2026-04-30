package com.lazydevs.notification.redis;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for Testcontainers-driven integration tests in this module.
 *
 * <p>Spins up a single Redis 7 Alpine container shared across all
 * test methods in the class (Testcontainers' default lifecycle when
 * the container field is {@code static}). Wires Redis's mapped port
 * into Spring's {@code spring.data.redis.*} via
 * {@link DynamicPropertySource} so subclasses don't need their own
 * connection-factory config.
 *
 * <p>Skipped automatically when Docker isn't available — Testcontainers
 * throws a clean {@code IllegalStateException} that surfaces as a test
 * skip rather than a failure. CI has Docker; local runs without it
 * just skip the integration suite.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractRedisIntegrationTest {

    @SuppressWarnings("resource") // managed by Testcontainers lifecycle
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @BeforeAll
    static void startRedis() {
        // Explicit start — @Testcontainers manages lifecycle for
        // @Container-annotated fields, but using a plain static field
        // keeps the test class portable across Testcontainers versions
        // (the @Container annotation moved packages between 1.x → 2.x).
        if (!REDIS.isRunning()) {
            REDIS.start();
        }
    }

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port",
                () -> REDIS.getMappedPort(6379).toString());
    }
}
