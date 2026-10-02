package com.lazydevs.notification.server;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.channel.push.fcm.FcmAccessTokenProviderFactory;
import com.lazydevs.notification.channel.push.fcm.googleauth.GoogleAuthTokenProviderFactory;
import com.lazydevs.notification.core.deadletter.InMemoryDeadLetterStore;
import com.lazydevs.notification.core.delivery.InMemoryDeliveryEventStore;
import com.lazydevs.notification.core.idempotency.CaffeineIdempotencyStore;
import com.lazydevs.notification.core.ratelimit.Bucket4jRateLimiter;
import com.lazydevs.notification.core.retry.RetryExecutor;
import com.lazydevs.notification.rest.controller.AdminController;
import com.lazydevs.notification.rest.controller.GlobalExceptionHandler;
import com.lazydevs.notification.rest.controller.NotificationController;
import com.lazydevs.notification.rest.filter.CallerAdmissionFilter;
import com.lazydevs.notification.rest.filter.TenantFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the default SPI implementations and the REST API register in the
 * standalone server, which scans only its own package, takes every
 * notification bean from the modules' auto-configurations and does not use
 * the starter.
 *
 * <p>Boots {@link NotificationServerApplication} with its real
 * {@code application.yml} and full auto-configuration, but in a mock
 * servlet context, so no port is opened.
 */
class DefaultBeansRegistrationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(NotificationServerApplication.class)
            .withPropertyValues(
                    "notification.kafka.enabled=false",
                    "notification.audit.enabled=false");

    @Test
    void withShippedConfiguration_registersCaffeineIdempotencyStore() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(NotificationService.class);
            assertThat(context).hasSingleBean(IdempotencyStore.class);
            assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(CaffeineIdempotencyStore.class);
        });
    }

    @Test
    void withShippedConfiguration_registersRestApi() {
        // application.yml sets notification.rest.enabled=true; REST is opt-in elsewhere.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(NotificationController.class);
            assertThat(context).hasSingleBean(AdminController.class);
            assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
            assertThat(context).hasSingleBean(TenantFilter.class);
            assertThat(context).hasBean("tenantFilterRegistration");
            assertThat(context.getBean("tenantFilterRegistration", FilterRegistrationBean.class).getFilter())
                    .isSameAs(context.getBean(TenantFilter.class));
            assertThat(context).hasSingleBean(CallerAdmissionFilter.class);
            assertThat(context).hasBean("callerAdmissionFilterRegistration");
        });
    }

    @Test
    void bundledAcsAndFcmProviders_registerPrototypeBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            for (String name : List.of("acsEmailProvider", "fcmPushProvider")) {
                assertThat(context.getBeanFactory().getBeanDefinition(name).isPrototype()).as(name).isTrue();
            }
            assertThat(ServiceLoader.load(FcmAccessTokenProviderFactory.class).stream().map(ServiceLoader.Provider::type))
                    .as("the google-auth adapter, for credentials: adc")
                    .contains(GoogleAuthTokenProviderFactory.class);
        });
    }

    @Test
    void withAllFeaturesEnabled_registersExactlyOneDefaultOfEachSpi() {
        runner.withPropertyValues(
                        "notification.dead-letter.enabled=true",
                        "notification.rate-limit.enabled=true",
                        "notification.retry.enabled=true",
                        "notification.delivery-events.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IdempotencyStore.class);
                    assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(CaffeineIdempotencyStore.class);
                    assertThat(context).hasSingleBean(DeadLetterStore.class);
                    assertThat(context.getBean(DeadLetterStore.class)).isInstanceOf(InMemoryDeadLetterStore.class);
                    assertThat(context).hasSingleBean(DeliveryEventStore.class);
                    assertThat(context.getBean(DeliveryEventStore.class)).isInstanceOf(InMemoryDeliveryEventStore.class);
                    assertThat(context).hasSingleBean(RateLimiter.class);
                    assertThat(context.getBean(RateLimiter.class)).isInstanceOf(Bucket4jRateLimiter.class);
                    assertThat(context).hasSingleBean(RetryExecutor.class);
                });
    }
}
