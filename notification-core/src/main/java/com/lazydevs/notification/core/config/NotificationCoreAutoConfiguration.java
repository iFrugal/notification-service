package com.lazydevs.notification.core.config;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.audit.NotificationAuditStore;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import com.lazydevs.notification.api.delivery.DeliveryEventPublisher;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.guard.RecipientGuard;
import com.lazydevs.notification.api.idempotency.IdempotencyReleasePolicy;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.push.PushDeviceRegistry;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.api.util.MessageScrubber;
import com.lazydevs.notification.api.webhook.WebhookHandler;
import com.lazydevs.notification.core.audit.StoreBackedAuditService;
import com.lazydevs.notification.core.caller.CallerRegistry;
import com.lazydevs.notification.core.delivery.ListenerDeliveryEventPublisher;
import com.lazydevs.notification.core.guard.ConfiguredRecipientGuard;
import com.lazydevs.notification.core.idempotency.FailurePolicyReleasePolicy;
import com.lazydevs.notification.core.metrics.NotificationMetrics;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.provider.ProviderResolver;
import com.lazydevs.notification.core.provider.ProviderRuntimeHints;
import com.lazydevs.notification.core.retry.RetryExecutor;
import com.lazydevs.notification.core.service.DefaultNotificationService;
import com.lazydevs.notification.core.service.NoOpAuditService;
import com.lazydevs.notification.core.service.NotificationServiceExtensions;
import com.lazydevs.notification.core.service.NotificationAuditService;
import com.lazydevs.notification.core.store.StoreTypeValidator;
import com.lazydevs.notification.core.push.PushContentRenderer;
import com.lazydevs.notification.core.push.PushTargetExpansion;
import com.lazydevs.notification.core.template.ChannelContentRenderer;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import com.lazydevs.notification.core.webhook.WebhookDispatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.io.ResourceLoader;

import java.time.Clock;
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

    /**
     * Routes provider callbacks to the {@link WebhookHandler} beans (DD-36).
     * Always present and inert without handlers: the REST module mounts it
     * when {@code notification.webhooks.enabled=true}, and an application
     * without that module can call it from its own endpoint.
     */
    @Bean
    @ConditionalOnMissingBean(WebhookDispatcher.class)
    public WebhookDispatcher webhookDispatcher(ObjectProvider<WebhookHandler> handlers,
                                               ObjectProvider<DeliveryEventListener> listeners,
                                               Optional<NotificationMetrics> metrics) {
        return new WebhookDispatcher(handlers.orderedStream().toList(),
                () -> listeners.orderedStream().iterator(), metrics);
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

    /**
     * Scrubs error messages before they are persisted (DD-32). Replace it by
     * declaring another {@link MessageScrubber} bean.
     */
    @Bean
    @ConditionalOnMissingBean(MessageScrubber.class)
    public MessageScrubber notificationMessageScrubber() {
        return MessageScrubber.placeholders();
    }

    /**
     * Decides whether a failed send releases its idempotency key, from
     * {@code notification.idempotency.failure-policy} (DD-32). Replace it by
     * declaring another {@link IdempotencyReleasePolicy} bean.
     */
    @Bean
    @ConditionalOnMissingBean(IdempotencyReleasePolicy.class)
    public FailurePolicyReleasePolicy idempotencyReleasePolicy(NotificationProperties properties) {
        return new FailurePolicyReleasePolicy(properties);
    }

    /**
     * The audit service of {@code notification.audit.enabled=true} (DD-33):
     * records go to the {@link NotificationAuditStore} of the selected store
     * family. Backs off when the application declares its own
     * {@link NotificationAuditService}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "notification.audit", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(NotificationAuditService.class)
    public StoreBackedAuditService storeBackedAuditService(ObjectProvider<NotificationAuditStore> stores,
                                                           NotificationProperties properties) {
        NotificationAuditStore store = stores.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("notification.audit.enabled=true needs a NotificationAuditStore:"
                    + " use notification.store.type=memory or jdbc (notification-store-jdbc), or declare a"
                    + " NotificationAuditStore or NotificationAuditService bean");
        }
        return new StoreBackedAuditService(store, properties.getDefaultTenant(), Clock.systemUTC());
    }

    /**
     * The configuration-driven recipient guard (DD-34), validated at
     * startup. Replace it by declaring another {@link RecipientGuard} bean,
     * which is used whether or not this switch is on.
     */
    @Bean
    @ConditionalOnProperty(prefix = "notification.recipient-guard", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(RecipientGuard.class)
    public ConfiguredRecipientGuard configuredRecipientGuard(NotificationProperties properties) {
        return new ConfiguredRecipientGuard(properties.getRecipientGuard());
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
            Optional<NotificationMetrics> metrics,
            MessageScrubber messageScrubber,
            Optional<DeliveryEventStore> deliveryEventStore,
            Optional<RecipientGuard> recipientGuard,
            Optional<IdempotencyReleasePolicy> releasePolicy,
            ObjectProvider<ChannelContentRenderer> renderers,
            ObjectProvider<PushDeviceRegistry> pushDeviceRegistry) {
        return new DefaultNotificationService(properties, providerRegistry, templateEngine, auditService,
                idempotencyStore, rateLimiter, retryExecutor, deadLetterStore, metrics,
                new NotificationServiceExtensions(messageScrubber, Clock.systemUTC(),
                        deliveryEventStore.orElse(null), recipientGuard.orElse(null),
                        releasePolicy.orElse(null), renderers.orderedStream().toList(),
                        pushTargetExpansion(properties, pushDeviceRegistry.getIfAvailable())));
    }

    /**
     * Push user targeting (DD-37) is on exactly when a {@link PushDeviceRegistry} bean
     * exists. {@code notification.push.devices.enabled=true} without one (the Redis store
     * family has no registry) fails startup instead of silently refusing every user target.
     */
    static PushTargetExpansion pushTargetExpansion(NotificationProperties properties, PushDeviceRegistry registry) {
        NotificationProperties.PushProperties push = properties.getPush();
        if (registry == null && push.getDevices().isEnabled()) {
            throw new IllegalStateException("notification.push.devices.enabled=true needs a PushDeviceRegistry:"
                    + " use notification.store.type=memory or jdbc (notification-store-jdbc), or declare a"
                    + " PushDeviceRegistry bean");
        }
        return new PushTargetExpansion(registry, push.getUserTargets().getMultiTokenPolicy());
    }

    /**
     * Renders push templates with {@code [TITLE]}, {@code [BODY]} and {@code [DATA key="..."]}
     * sections (DD-37); a template without sections renders as before. Turn it off with
     * {@code notification.push.template-sections.enabled=false} to declare another
     * {@link ChannelContentRenderer} for PUSH.
     */
    @Bean
    @ConditionalOnProperty(prefix = "notification.push.template-sections", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public PushContentRenderer pushContentRenderer(NotificationTemplateEngine templateEngine) {
        return new PushContentRenderer(templateEngine);
    }
}
