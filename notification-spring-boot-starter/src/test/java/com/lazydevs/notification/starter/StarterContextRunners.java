package com.lazydevs.notification.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.util.ClassUtils;

/**
 * Builds context runners from the notification modules' own
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * files, so the tests exercise exactly what a Boot application gets when it
 * adds the starter, not a hand-picked list of configuration classes.
 */
final class StarterContextRunners {

    private static final String NOTIFICATION_PACKAGE_PREFIX = "com.lazydevs.notification.";

    private StarterContextRunners() {
    }

    /** Every notification auto-configuration found on the test classpath. */
    static AutoConfigurations notificationAutoConfigurations() {
        ClassLoader classLoader = StarterContextRunners.class.getClassLoader();
        Class<?>[] classes = ImportCandidates.load(AutoConfiguration.class, classLoader)
                .getCandidates().stream()
                .filter(name -> name.startsWith(NOTIFICATION_PACKAGE_PREFIX))
                .map(name -> ClassUtils.resolveClassName(name, classLoader))
                .toArray(Class<?>[]::new);
        return AutoConfigurations.of(classes);
    }

    /**
     * A servlet web application, because the starter's test classpath carries
     * the optional notification-rest module and its filters need Spring MVC.
     */
    static WebApplicationContextRunner starterRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(notificationAutoConfigurations())
                .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class));
    }
}
