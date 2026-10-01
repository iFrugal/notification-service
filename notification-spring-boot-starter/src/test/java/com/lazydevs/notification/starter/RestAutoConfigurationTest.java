package com.lazydevs.notification.starter;

import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.core.delivery.InMemoryDeliveryEventStore;
import com.lazydevs.notification.rest.controller.AdminController;
import com.lazydevs.notification.rest.controller.GlobalExceptionHandler;
import com.lazydevs.notification.rest.controller.NotificationController;
import com.lazydevs.notification.rest.filter.CallerAdmissionFilter;
import com.lazydevs.notification.rest.filter.TenantFilter;
import com.lazydevs.notification.rest.webhook.LoggingDeliveryEventListener;
import com.lazydevs.notification.rest.webhook.WebhookController;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the REST API is opt-in in starter mode: nothing from
 * notification-rest registers unless {@code notification.rest.enabled=true}
 * in a servlet application, and when it does, the filters and the
 * exception handler stay inside the notification endpoints.
 */
class RestAutoConfigurationTest {

    private static final String REST_ON = "notification.rest.enabled=true";
    private static final String WEBHOOKS_ON = "notification.webhooks.enabled=true";

    private final WebApplicationContextRunner runner = StarterContextRunners.starterRunner();

    @Test
    void restDefault_registersNoControllersFiltersAdviceOpenApiOrListener() {
        runner.withPropertyValues(WEBHOOKS_ON).run(context -> {
            assertThat(context).hasNotFailed();
            assertNoRestBeans(context);
        });
    }

    @Test
    void restDisabledWebhooksEnabled_registersNoWebhookBeans() {
        runner.withPropertyValues("notification.rest.enabled=false", WEBHOOKS_ON,
                        "notification.webhooks.twilio.enabled=true",
                        "notification.webhooks.twilio.auth-token=test-token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(WebhookController.class);
                    assertThat(context).doesNotHaveBean(LoggingDeliveryEventListener.class);
                    assertNoRestBeans(context);
                });
    }

    @Test
    void restEnabled_registersControllersFiltersAdviceAndOpenApi() {
        runner.withPropertyValues(REST_ON).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(NotificationController.class);
            assertThat(context).hasSingleBean(AdminController.class);
            assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
            assertThat(context).hasSingleBean(TenantFilter.class);
            assertThat(context).hasSingleBean(CallerAdmissionFilter.class);
            assertThat(context).hasBean("tenantFilterRegistration");
            assertThat(context).hasBean("callerAdmissionFilterRegistration");
            assertThat(context).hasSingleBean(OpenAPI.class);
            // Webhooks are a second opt-in on top of REST.
            assertThat(context).doesNotHaveBean(WebhookController.class);
            assertThat(context).doesNotHaveBean(LoggingDeliveryEventListener.class);

            // Controllers declared by @Bean are still picked up by Spring MVC.
            assertThat(mappedPatterns(context)).contains(
                    "/api/v1/notifications", "/api/v1/notifications/batch", "/api/v1/admin/configuration");
        });
    }

    @Test
    void restEnabled_filtersKeepOrderAndBasePathPattern() {
        runner.withPropertyValues(REST_ON).run(context -> {
            assertThat(context).hasNotFailed();
            FilterRegistrationBean<?> tenant = registration(context, "tenantFilterRegistration");
            FilterRegistrationBean<?> admission = registration(context, "callerAdmissionFilterRegistration");

            assertThat(tenant.getFilter()).isSameAs(context.getBean(TenantFilter.class));
            assertThat(tenant.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
            assertThat(tenant.getUrlPatterns()).containsExactly("/api/v1/*");

            assertThat(admission.getFilter()).isSameAs(context.getBean(CallerAdmissionFilter.class));
            assertThat(admission.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
            assertThat(admission.getUrlPatterns()).containsExactly("/api/v1/*");
        });
    }

    @Test
    void restEnabled_customBasePathWithTrailingSlash_filtersFollowBasePath() {
        runner.withPropertyValues(REST_ON, "notification.rest.base-path=/notify/").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(registration(context, "tenantFilterRegistration").getUrlPatterns())
                    .containsExactly("/notify/*");
            assertThat(registration(context, "callerAdmissionFilterRegistration").getUrlPatterns())
                    .containsExactly("/notify/*");
        });
    }

    @Test
    void restEnabled_exceptionHandlerAppliesOnlyToNotificationControllers() {
        runner.withPropertyValues(REST_ON).run(context -> {
            assertThat(context).hasNotFailed();
            List<ControllerAdviceBean> advice = ControllerAdviceBean.findAnnotatedBeans(context).stream()
                    .filter(bean -> GlobalExceptionHandler.class.equals(bean.getBeanType()))
                    .toList();
            assertThat(advice).hasSize(1);
            ControllerAdviceBean handler = advice.get(0);
            assertThat(handler.isApplicableToBeanType(NotificationController.class)).isTrue();
            assertThat(handler.isApplicableToBeanType(AdminController.class)).isTrue();
            assertThat(handler.isApplicableToBeanType(WebhookController.class)).isTrue();
            assertThat(handler.isApplicableToBeanType(HostController.class)).isFalse();
        });
    }

    @Test
    void restEnabled_withoutSpringdoc_registersControllersButNoOpenApi() {
        runner.withPropertyValues(REST_ON)
                .withClassLoader(new FilteredClassLoader(OpenAPI.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(NotificationController.class);
                    assertThat(context).doesNotHaveBean("notificationServiceOpenAPI");
                });
    }

    @Test
    void restEnabled_apiDocsDisabled_registersNoOpenApi() {
        runner.withPropertyValues(REST_ON, "springdoc.api-docs.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(NotificationController.class);
            assertThat(context).doesNotHaveBean(OpenAPI.class);
        });
    }

    @Test
    void webhooksEnabled_registersLoggingListener() {
        runner.withPropertyValues(REST_ON, WEBHOOKS_ON).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WebhookController.class);
            assertThat(context).hasSingleBean(DeliveryEventListener.class);
            assertThat(context).hasSingleBean(LoggingDeliveryEventListener.class);
            assertThat(webhookListeners(context))
                    .containsExactly(context.getBean(LoggingDeliveryEventListener.class));
        });
    }

    @Test
    void webhooksEnabled_userListenerSuppressesLoggingListener() {
        runner.withPropertyValues(REST_ON, WEBHOOKS_ON)
                .withUserConfiguration(UserListenerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LoggingDeliveryEventListener.class);
                    assertThat(context).hasSingleBean(DeliveryEventListener.class);
                    assertThat(webhookListeners(context))
                            .containsExactly(context.getBean("userDeliveryEventListener", DeliveryEventListener.class));
                });
    }

    @Test
    void webhooksEnabled_deliveryEventStoreSuppressesLoggingListener() {
        // DeliveryEventStore extends DeliveryEventListener: a store already
        // consumes every event, so the logging fallback steps aside.
        runner.withPropertyValues(REST_ON, WEBHOOKS_ON, "notification.delivery-events.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LoggingDeliveryEventListener.class);
                    assertThat(context).hasSingleBean(DeliveryEventStore.class);
                    assertThat(webhookListeners(context))
                            .containsExactly(context.getBean(InMemoryDeliveryEventStore.class));
                });
    }

    @Test
    void restEnabled_nonWebApplication_registersNothing() {
        StarterContextRunners.starterNonWebRunner()
                .withPropertyValues(REST_ON, WEBHOOKS_ON)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(NotificationController.class);
                    assertThat(context).doesNotHaveBean(AdminController.class);
                    assertThat(context).doesNotHaveBean(WebhookController.class);
                    assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class);
                    assertThat(context).doesNotHaveBean(TenantFilter.class);
                    assertThat(context).doesNotHaveBean(CallerAdmissionFilter.class);
                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                    assertThat(context).doesNotHaveBean(OpenAPI.class);
                    assertThat(context).doesNotHaveBean(LoggingDeliveryEventListener.class);
                });
    }

    private static void assertNoRestBeans(AssertableWebApplicationContext context) {
        assertThat(context).doesNotHaveBean(NotificationController.class);
        assertThat(context).doesNotHaveBean(AdminController.class);
        assertThat(context).doesNotHaveBean(WebhookController.class);
        assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class);
        assertThat(context).doesNotHaveBean(TenantFilter.class);
        assertThat(context).doesNotHaveBean(CallerAdmissionFilter.class);
        assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
        assertThat(context).doesNotHaveBean(OpenAPI.class);
        assertThat(context).doesNotHaveBean(LoggingDeliveryEventListener.class);
    }

    private static FilterRegistrationBean<?> registration(AssertableWebApplicationContext context, String name) {
        return context.getBean(name, FilterRegistrationBean.class);
    }

    @SuppressWarnings("unchecked")
    private static List<DeliveryEventListener> webhookListeners(AssertableWebApplicationContext context) {
        return (List<DeliveryEventListener>) ReflectionTestUtils.getField(
                context.getBean(WebhookController.class), "listeners");
    }

    private static List<String> mappedPatterns(AssertableWebApplicationContext context) {
        return context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
                .getHandlerMethods().keySet().stream()
                .map(RequestMappingInfo::getPatternValues)
                .flatMap(java.util.Set::stream)
                .toList();
    }

    @Configuration(proxyBeanMethods = false)
    static class UserListenerConfiguration {
        @Bean
        DeliveryEventListener userDeliveryEventListener() {
            return event -> {
                // no-op listener
            };
        }
    }

    /** Stands in for a controller of the host application. */
    @RestController
    static class HostController {
    }
}
