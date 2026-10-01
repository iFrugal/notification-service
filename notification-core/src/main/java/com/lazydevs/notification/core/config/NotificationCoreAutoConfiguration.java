package com.lazydevs.notification.core.config;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.caller.CallerRegistry;
import com.lazydevs.notification.core.metrics.NotificationMetrics;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.provider.ProviderResolver;
import com.lazydevs.notification.core.retry.RetryExecutor;
import com.lazydevs.notification.core.service.DefaultNotificationService;
import com.lazydevs.notification.core.service.NoOpAuditService;
import com.lazydevs.notification.core.service.NotificationAuditService;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

import java.util.Optional;

/**
 * Registers the core notification beans: the send pipeline, provider
 * registry, template engine, caller registry and the no-op audit default.
 *
 * <p>Listed in {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports},
 * so both the starter and the standalone server get these beans without
 * component-scanning {@code com.lazydevs.notification.core}.
 * Bean names match the class-derived names the scanned components used to have.
 */
@AutoConfiguration
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationCoreAutoConfiguration {

    @Bean
    public CallerRegistry callerRegistry(NotificationProperties properties) {
        return new CallerRegistry(properties);
    }

    @Bean
    public ProviderResolver providerResolver(ApplicationContext applicationContext) {
        return new ProviderResolver(applicationContext);
    }

    @Bean
    public ProviderRegistry providerRegistry(NotificationProperties properties, ProviderResolver providerResolver) {
        return new ProviderRegistry(properties, providerResolver);
    }

    @Bean
    public NotificationTemplateEngine notificationTemplateEngine(NotificationProperties properties,
                                                                 ResourceLoader resourceLoader) {
        return new NotificationTemplateEngine(properties, resourceLoader);
    }

    @Bean
    @ConditionalOnProperty(prefix = "notification.audit", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(NotificationAuditService.class)
    public NoOpAuditService noOpAuditService() {
        return new NoOpAuditService();
    }

    @Bean
    @ConditionalOnMissingBean(NotificationService.class)
    public DefaultNotificationService defaultNotificationService(
            NotificationProperties properties,
            ProviderRegistry providerRegistry,
            NotificationTemplateEngine templateEngine,
            NotificationAuditService auditService,
            Optional<IdempotencyStore> idempotencyStore,
            Optional<RateLimiter> rateLimiter,
            Optional<RetryExecutor> retryExecutor,
            Optional<DeadLetterStore> deadLetterStore,
            Optional<NotificationMetrics> metrics) {
        return new DefaultNotificationService(properties, providerRegistry, templateEngine, auditService,
                idempotencyStore, rateLimiter, retryExecutor, deadLetterStore, metrics);
    }
}
