package com.lazydevs.notification.core.config;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import com.lazydevs.notification.api.delivery.DeliveryEventPublisher;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.caller.CallerRegistry;
import com.lazydevs.notification.core.delivery.ListenerDeliveryEventPublisher;
import com.lazydevs.notification.core.metrics.NotificationMetrics;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.provider.ProviderResolver;
import com.lazydevs.notification.core.provider.ProviderRuntimeHints;
import com.lazydevs.notification.core.retry.RetryExecutor;
import com.lazydevs.notification.core.service.DefaultNotificationService;
import com.lazydevs.notification.core.service.NoOpAuditService;
import com.lazydevs.notification.core.service.NotificationAuditService;
import com.lazydevs.notification.core.store.StoreTypeValidator;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.io.ResourceLoader;

import java.util.Optional;

/**
 * Registers the core notification beans: the send pipeline, provider
 * registry, template engine, caller registry and the no-op audit default,
 * plus the {@link StoreTypeValidator} that fails startup when the selected
 * store family's module is missing.
 *
 * <p>Listed in {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports},
 * so both the starter and the standalone server get these beans without
 * component-scanning {@code com.lazydevs.notification.core}.
 * Bean names match the class-derived names the scanned components used to have.
 */
@AutoConfiguration
@EnableConfigurationProperties(NotificationProperties.class)
@ImportRuntimeHints(ProviderRuntimeHints.class)
public class NotificationCoreAutoConfiguration {

    /**
     * Static so the validator, a {@code BeanFactoryPostProcessor}, does not
     * force this configuration class to be instantiated early.
     */
    @Bean
    public static StoreTypeValidator storeTypeValidator() {
        return new StoreTypeValidator();
    }

    @Bean
    public CallerRegistry callerRegistry(NotificationProperties properties) {
        return new CallerRegistry(properties);
    }

    @Bean
    public ProviderResolver providerResolver(ApplicationContext applicationContext) {
        return new ProviderResolver(applicationContext);
    }

    /**
     * Hands events that providers publish while sending (DD-25) to every
     * {@link DeliveryEventListener} bean. Replace it by declaring another
     * {@link DeliveryEventPublisher} bean.
     */
    @Bean
    @ConditionalOnMissingBean(DeliveryEventPublisher.class)
    public ListenerDeliveryEventPublisher deliveryEventPublisher(ObjectProvider<DeliveryEventListener> listeners,
                                                                 Optional<NotificationMetrics> metrics) {
        return new ListenerDeliveryEventPublisher(listeners, metrics);
    }

    @Bean
    public ProviderRegistry providerRegistry(NotificationProperties properties, ProviderResolver providerResolver,
                                             DeliveryEventPublisher deliveryEventPublisher) {
        return new ProviderRegistry(properties, providerResolver, deliveryEventPublisher);
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
