package com.lazydevs.notification.rest.autoconfigure;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.caller.CallerRegistry;
import com.lazydevs.notification.core.config.NotificationCoreDefaultsAutoConfiguration;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.metrics.NotificationMetrics;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.service.NotificationAuditService;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import com.lazydevs.notification.rest.controller.AdminController;
import com.lazydevs.notification.rest.controller.GlobalExceptionHandler;
import com.lazydevs.notification.rest.controller.NotificationController;
import com.lazydevs.notification.rest.filter.CallerAdmissionFilter;
import com.lazydevs.notification.rest.filter.TenantFilter;
import com.lazydevs.notification.rest.openapi.OpenApiConfig;
import com.lazydevs.notification.rest.webhook.LoggingDeliveryEventListener;
import com.lazydevs.notification.rest.webhook.WebhookController;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

import java.util.Optional;

/**
 * Registers the notification REST API: the send and admin controllers,
 * their exception handler, the tenant and caller-admission filters, the
 * OpenAPI metadata and, behind a second flag, the provider webhooks.
 *
 * <p>REST is opt-in.
 * Nothing here registers unless {@code notification.rest.enabled=true} is
 * set in a servlet web application, so a host that embeds the starter does
 * not grow HTTP endpoints or servlet filters it never asked for.
 * The filters are restricted to {@code <notification.rest.base-path>/*}
 * and the exception handler to the notification controllers, so neither
 * touches the host's own endpoints.
 *
 * <p>Ordered after the core defaults and the Redis auto-configuration so
 * that {@link ConditionalOnMissingBean} on the logging delivery-event
 * listener sees any {@link DeliveryEventStore} those register.
 *
 * <p>Listed in {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * Bean names match the class-derived names the scanned components used to have.
 */
@AutoConfiguration(after = NotificationCoreDefaultsAutoConfiguration.class,
        afterName = "com.lazydevs.notification.redis.autoconfigure.NotificationRedisAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty("notification.rest.enabled")
@EnableConfigurationProperties(NotificationProperties.class)
@Import(OpenApiConfig.class)
public class NotificationRestAutoConfiguration {

    /** Order of {@link TenantFilter}: first, so every later filter sees the tenant and caller id. */
    public static final int TENANT_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE;

    /** Order of {@link CallerAdmissionFilter}: after {@link TenantFilter} has read the caller id. */
    public static final int CALLER_ADMISSION_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    public NotificationController notificationController(NotificationService notificationService) {
        return new NotificationController(notificationService);
    }

    @Bean
    public AdminController adminController(NotificationProperties properties,
                                           ProviderRegistry providerRegistry,
                                           NotificationTemplateEngine templateEngine,
                                           CallerRegistry callerRegistry,
                                           Optional<RateLimiter> rateLimiter,
                                           Optional<DeadLetterStore> deadLetterStore,
                                           Optional<DeliveryEventStore> deliveryEventStore,
                                           NotificationService notificationService,
                                           NotificationAuditService auditService) {
        return new AdminController(properties, providerRegistry, templateEngine, callerRegistry,
                rateLimiter, deadLetterStore, deliveryEventStore, notificationService, auditService);
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /**
     * Declared as a bean, not only inside its registration, because its
     * base class field-injects the MVC {@code HandlerExceptionResolver}.
     * Boot does not register a filter bean a second time when a
     * {@link FilterRegistrationBean} already wraps it.
     */
    @Bean
    public TenantFilter tenantFilter(NotificationProperties properties) {
        return new TenantFilter(properties);
    }

    @Bean
    public FilterRegistrationBean<TenantFilter> tenantFilterRegistration(TenantFilter tenantFilter,
                                                                         NotificationProperties properties) {
        return restrictedRegistration(tenantFilter, TENANT_FILTER_ORDER, properties);
    }

    @Bean
    public CallerAdmissionFilter callerAdmissionFilter(CallerRegistry callerRegistry) {
        return new CallerAdmissionFilter(callerRegistry);
    }

    @Bean
    public FilterRegistrationBean<CallerAdmissionFilter> callerAdmissionFilterRegistration(
            CallerAdmissionFilter callerAdmissionFilter, NotificationProperties properties) {
        return restrictedRegistration(callerAdmissionFilter, CALLER_ADMISSION_FILTER_ORDER, properties);
    }

    private static <F extends Filter> FilterRegistrationBean<F> restrictedRegistration(
            F filter, int order, NotificationProperties properties) {
        FilterRegistrationBean<F> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(order);
        registration.addUrlPatterns(urlPattern(properties.getRest().getBasePath()));
        return registration;
    }

    /**
     * Servlet URL pattern covering everything under the REST base path.
     * Trailing slashes are dropped, so {@code /api/v1/} and {@code /api/v1}
     * both yield {@code /api/v1/*}; an empty base path yields {@code /*}.
     */
    static String urlPattern(String basePath) {
        String base = basePath == null ? "" : basePath.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/*";
    }

    /**
     * Provider delivery-callback webhooks (DD-16), opt-in on top of REST.
     * The per-provider Twilio and SES flags are checked inside the handlers.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty("notification.webhooks.enabled")
    static class WebhookConfiguration {

        @Bean
        public WebhookController webhookController(NotificationProperties properties,
                                                   ObjectProvider<DeliveryEventListener> listeners,
                                                   Optional<NotificationMetrics> metrics) {
            return new WebhookController(properties, listeners.orderedStream().toList(), metrics);
        }

        /**
         * Logs delivery events when nothing else consumes them.
         * Any {@link DeliveryEventListener} bean suppresses it, including a
         * {@link DeliveryEventStore} (which extends the listener interface),
         * so enabling {@code notification.delivery-events} replaces logging
         * with storage.
         */
        @Bean
        @ConditionalOnMissingBean(DeliveryEventListener.class)
        public LoggingDeliveryEventListener loggingDeliveryEventListener() {
            return new LoggingDeliveryEventListener();
        }
    }
}
