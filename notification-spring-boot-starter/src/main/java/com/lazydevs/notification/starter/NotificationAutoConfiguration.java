package com.lazydevs.notification.starter;

import com.lazydevs.notification.core.config.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot auto-configuration for notification service.
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
})
public class NotificationAutoConfiguration {

    public NotificationAutoConfiguration() {
        log.info("Notification service auto-configuration loaded");
    }
}
