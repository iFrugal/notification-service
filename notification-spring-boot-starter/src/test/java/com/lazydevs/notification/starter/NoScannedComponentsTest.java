package com.lazydevs.notification.starter;

import com.lazydevs.notification.core.config.NotificationCoreAutoConfiguration;
import com.lazydevs.notification.core.config.NotificationCoreDefaultsAutoConfiguration;
import com.lazydevs.notification.kafka.autoconfigure.NotificationKafkaAutoConfiguration;
import com.lazydevs.notification.redis.autoconfigure.NotificationRedisAutoConfiguration;
import com.lazydevs.notification.rest.autoconfigure.NotificationRestAutoConfiguration;
import com.lazydevs.notification.rest.controller.AdminController;
import com.lazydevs.notification.rest.controller.GlobalExceptionHandler;
import com.lazydevs.notification.rest.controller.NotificationController;
import com.lazydevs.notification.rest.webhook.WebhookController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against component scanning creeping back in.
 *
 * <p>Neither the starter nor the standalone server scans
 * {@code com.lazydevs.notification}; every bean comes from an
 * auto-configuration.
 * A stereotype annotation on a library class would therefore be dead
 * weight at best, and a duplicate bean in a host that does scan the
 * package at worst.
 * The only stereotypes left are the ones Spring MVC needs to find the
 * notification controllers and their exception handler, and those classes
 * are registered by {@code NotificationRestAutoConfiguration}, not by a scan.
 *
 * <p>Covers every notification module on this test classpath (api, core,
 * kafka, redis, rest and the starter itself); the provider modules are not
 * dependencies of the starter.
 * Auto-configurations and their nested classes are excluded, as
 * {@code @SpringBootApplication} excludes them, and so are this module's
 * test classes.
 */
class NoScannedComponentsTest {

    @Test
    void onlyMvcStereotypesRemain() {
        Set<String> skippedAutoConfigurations = new TreeSet<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(true);
        scanner.addExcludeFilter((reader, factory) -> isTestClass(reader));
        scanner.addExcludeFilter((reader, factory) -> {
            boolean autoConfiguration = isAutoConfigurationOrNestedInOne(reader, factory);
            if (autoConfiguration) {
                skippedAutoConfigurations.add(reader.getClassMetadata().getClassName());
            }
            return autoConfiguration;
        });

        Set<String> candidates = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.lazydevs.notification")) {
            candidates.add(candidate.getBeanClassName());
        }

        assertThat(candidates).containsExactlyInAnyOrder(
                NotificationController.class.getName(),
                AdminController.class.getName(),
                WebhookController.class.getName(),
                GlobalExceptionHandler.class.getName());

        // The scan really walked the other modules: their auto-configurations
        // were seen and set aside.
        assertThat(skippedAutoConfigurations).contains(
                NotificationAutoConfiguration.class.getName(),
                NotificationCoreAutoConfiguration.class.getName(),
                NotificationCoreDefaultsAutoConfiguration.class.getName(),
                NotificationKafkaAutoConfiguration.class.getName(),
                NotificationRedisAutoConfiguration.class.getName(),
                NotificationRestAutoConfiguration.class.getName(),
                NotificationRestAutoConfiguration.class.getName() + "$WebhookConfiguration");
    }

    private static boolean isTestClass(MetadataReader reader) throws IOException {
        return reader.getResource().getURL().toString().contains("/test-classes/");
    }

    private static boolean isAutoConfigurationOrNestedInOne(MetadataReader reader, MetadataReaderFactory factory)
            throws IOException {
        MetadataReader current = reader;
        while (true) {
            if (current.getAnnotationMetadata().hasAnnotation(AutoConfiguration.class.getName())) {
                return true;
            }
            ClassMetadata metadata = current.getClassMetadata();
            if (!metadata.hasEnclosingClass()) {
                return false;
            }
            current = factory.getMetadataReader(metadata.getEnclosingClassName());
        }
    }
}
