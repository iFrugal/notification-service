package com.lazydevs.notification.core.config;

import com.lazydevs.notification.core.config.NotificationProperties.RateLimitBandwidth;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitScope;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Binds DD-30 rate-limit configuration the way Spring Boot does at startup,
 * so the "bandwidths or single band, not both" rule is proven against real
 * property binding rather than hand-called setters.
 */
class RateLimitRuleBindingTest {

    private static ValidatorFactory factory;

    @BeforeAll
    static void initValidator() {
        factory = Validation.byDefaultProvider()
                .configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static NotificationProperties bind(Map<String, String> source) {
        Binder binder = new Binder(new MapConfigurationPropertySource(source));
        return binder.bind("notification", Bindable.of(NotificationProperties.class),
                        new ValidationBindHandler(new SpringValidatorAdapter(factory.getValidator())))
                .get();
    }

    @Test
    void bandwidthsAndScope_bindOnTheDefaultRule() {
        NotificationProperties properties = bind(Map.of(
                "notification.rate-limit.default-rule.scope", "channel",
                "notification.rate-limit.default-rule.bandwidths[0].capacity", "2",
                "notification.rate-limit.default-rule.bandwidths[0].refill-tokens", "2",
                "notification.rate-limit.default-rule.bandwidths[0].refill-period", "1m",
                "notification.rate-limit.default-rule.bandwidths[1].capacity", "5",
                "notification.rate-limit.default-rule.bandwidths[1].refill-tokens", "5",
                "notification.rate-limit.default-rule.bandwidths[1].refill-period", "1h",
                "notification.rate-limit.refund-unsent", "true",
                "notification.rate-limit.by-channel.email.scope", "tenant-caller"));

        assertThat(properties.getRateLimit().getDefaultRule().getScope()).isEqualTo(RateLimitScope.CHANNEL);
        assertThat(properties.getRateLimit().getDefaultRule().effectiveBandwidths()).containsExactly(
                new RateLimitBandwidth(2, 2, Duration.ofMinutes(1)),
                new RateLimitBandwidth(5, 5, Duration.ofHours(1)));
        assertThat(properties.getRateLimit().isRefundUnsent()).isTrue();
        assertThat(properties.getRateLimit().getByChannel().get("email").getScope())
                .isEqualTo(RateLimitScope.TENANT_CALLER);
    }

    @Test
    void defaults_keepTheSingleBandTenantCallerRuleAndNoRefund() {
        NotificationProperties properties = bind(Map.of("notification.default-tenant", "default"));

        assertThat(properties.getRateLimit().getDefaultRule().getScope()).isEqualTo(RateLimitScope.TENANT_CALLER);
        assertThat(properties.getRateLimit().getDefaultRule().effectiveBandwidths())
                .containsExactly(new RateLimitBandwidth(200, 100, Duration.ofSeconds(1)));
        assertThat(properties.getRateLimit().isRefundUnsent()).isFalse();
        assertThat(properties.getTenantFallback().isEnabled()).isFalse();
        assertThat(properties.getProviders().isLazyInit()).isFalse();
    }

    @Test
    void bandwidthsTogetherWithCapacity_failBindingWithAClearMessage() {
        Map<String, String> source = Map.of(
                "notification.rate-limit.by-channel.sms.capacity", "10",
                "notification.rate-limit.by-channel.sms.bandwidths[0].capacity", "2",
                "notification.rate-limit.by-channel.sms.bandwidths[0].refill-tokens", "2",
                "notification.rate-limit.by-channel.sms.bandwidths[0].refill-period", "1m");

        assertThatThrownBy(() -> bind(source))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessageContaining("sets both 'bandwidths' and 'capacity'/'refill-tokens'/'refill-period'");
    }

    @Test
    void tenantFallbackAndLazyInit_bind() {
        NotificationProperties properties = bind(Map.of(
                "notification.tenant-fallback.enabled", "true",
                "notification.providers.lazy-init", "true"));

        assertThat(properties.getTenantFallback().isEnabled()).isTrue();
        assertThat(properties.getProviders().isLazyInit()).isTrue();
    }
}
