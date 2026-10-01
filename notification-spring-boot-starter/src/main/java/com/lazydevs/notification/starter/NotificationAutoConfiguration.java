package com.lazydevs.notification.starter;

import com.lazydevs.notification.core.config.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Spring Boot auto-configuration for notification service.
 *
 * <p>Core, Redis and Kafka beans come from their modules' own
 * auto-configurations, listed in each module's
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * Only the optional REST module is still component-scanned; auto-configurations
 * are excluded from that scan, as {@code @SpringBootApplication} does.
 */
@Slf4j
@AutoConfiguration
@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
@ComponentScan(basePackages = {
        "com.lazydevs.notification.rest"
}, excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM,
        classes = AutoConfigurationExcludeFilter.class))
public class NotificationAutoConfiguration {

    public NotificationAutoConfiguration() {
        log.info("Notification service auto-configuration loaded");
    }
}
