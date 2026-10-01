package com.lazydevs.notification.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone notification service application.
 * For Docker deployment.
 *
 * <p>Scans only its own package, which holds nothing but this class.
 * Every notification bean comes from the modules' auto-configurations
 * (core also enables {@code NotificationProperties}), the same way the
 * starter gets them; the REST API is switched on by
 * {@code notification.rest.enabled=true} in {@code application.yml}.
 */
@SpringBootApplication
public class NotificationServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServerApplication.class, args);
    }
}
