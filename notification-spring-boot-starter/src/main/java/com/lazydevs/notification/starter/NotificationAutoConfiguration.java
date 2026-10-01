package com.lazydevs.notification.starter;

import com.lazydevs.notification.core.config.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring Boot auto-configuration entry point for the notification service.
 *
 * <p>This class registers no beans and scans no packages.
 * Every bean comes from the per-module auto-configurations listed in each
 * module's {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}:
 * core, Redis and Kafka, and the opt-in REST API
 * ({@code notification.rest.enabled=true}).
 * The class stays so that existing {@code spring.autoconfigure.exclude}
 * entries naming it still resolve.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationAutoConfiguration {

    public NotificationAutoConfiguration() {
        log.info("Notification service auto-configuration loaded");
    }
}
