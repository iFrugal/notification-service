package com.lazydevs.notification.starter;

import com.lazydevs.notification.core.config.NotificationCoreDefaultsAutoConfiguration;
import com.lazydevs.notification.core.config.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * Spring Boot auto-configuration for notification service.
 *
 * <p>Replaceable defaults come from {@link NotificationCoreDefaultsAutoConfiguration},
 * which is imported rather than scanned: auto-configurations are excluded
 * from the component scan, as {@code @SpringBootApplication} does.
 */
@Slf4j
@AutoConfiguration
@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
@ComponentScan(basePackages = {
        "com.lazydevs.notification.core",
        "com.lazydevs.notification.rest",
        "com.lazydevs.notification.kafka",
        "com.lazydevs.notification.audit"
}, excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM,
        classes = AutoConfigurationExcludeFilter.class))
@Import(NotificationCoreDefaultsAutoConfiguration.class)
public class NotificationAutoConfiguration {

    public NotificationAutoConfiguration() {
        log.info("Notification service auto-configuration loaded");
    }
}
