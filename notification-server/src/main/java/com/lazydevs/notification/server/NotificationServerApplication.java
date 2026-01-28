package com.lazydevs.notification.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone notification service application.
 * For Docker deployment.
 */
@SpringBootApplication(scanBasePackages = {
        "com.lazydevs.notification"
})
public class NotificationServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServerApplication.class, args);
    }
}
