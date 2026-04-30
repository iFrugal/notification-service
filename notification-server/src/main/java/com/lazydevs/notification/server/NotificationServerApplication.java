package com.lazydevs.notification.server;

import com.lazydevs.notification.core.config.NotificationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Standalone notification service application.
 * For Docker deployment.
 *
 * <p>{@code @EnableConfigurationProperties(NotificationProperties.class)}
 * is needed here because this module deliberately doesn't depend on
 * {@code notification-spring-boot-starter} — keeping the standalone
 * deployable independent of the embeddable-library packaging. The
 * starter pulls the same enablement via its auto-configuration; this
 * server pulls it explicitly so {@link NotificationProperties} (the
 * config bean every conditional in {@code notification-core} reads)
 * is available regardless of which deployment shape an operator picks.
 */
@SpringBootApplication(scanBasePackages = {
        "com.lazydevs.notification"
})
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServerApplication.class, args);
    }
}
