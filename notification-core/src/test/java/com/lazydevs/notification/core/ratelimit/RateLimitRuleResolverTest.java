package com.lazydevs.notification.core.ratelimit;

import com.lazydevs.notification.api.ratelimit.RateLimiter.RateLimitKey;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitBandwidth;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitOverride;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitProperties;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitRule;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitScope;
import io.github.bucket4j.BucketConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule precedence and rule ids of {@link RateLimitRuleResolver} (DD-12,
 * DD-23, DD-30).
 */
class RateLimitRuleResolverTest {

    private static final RateLimitKey ACME_BILLING_SMS = new RateLimitKey("acme", "billing", "sms");

    @Test
    void precedence_tupleThenTenantCallerThenTenantThenByChannelThenDefault() {
        RateLimitProperties config = new RateLimitProperties();
        config.getByChannel().put("sms", new RateLimitRule(5, 5, Duration.ofSeconds(1)));
        RateLimitOverride tenantOnly = override("acme", null, null);
        RateLimitOverride tenantCaller = override("acme", "billing", null);
        RateLimitOverride tuple = override("acme", "billing", "SMS");
        config.setOverrides(List.of(tenantOnly, tenantCaller, tuple));
        RateLimitRuleResolver resolver = new RateLimitRuleResolver(config);

        assertThat(resolver.overrideCount()).isEqualTo(3);
        assertThat(resolver.resolve(ACME_BILLING_SMS).ruleId()).isEqualTo("override:2");
        assertThat(resolver.resolve(new RateLimitKey("acme", "billing", "email")).ruleId()).isEqualTo("override:1");
        assertThat(resolver.resolve(new RateLimitKey("acme", "ops", "email")).ruleId()).isEqualTo("override:0");
        assertThat(resolver.resolve(new RateLimitKey("globex", "ops", "SMS")).ruleId()).isEqualTo("by-channel:sms");
        assertThat(resolver.resolve(new RateLimitKey("globex", "ops", "email")).ruleId()).isEqualTo("default");
    }

    @Test
    void sameSpecificity_configurationOrderWins() {
        RateLimitProperties config = new RateLimitProperties();
        config.setOverrides(List.of(override("acme", null, null), override("acme", null, null)));

        assertThat(new RateLimitRuleResolver(config).resolve(ACME_BILLING_SMS).ruleId()).isEqualTo("override:0");
    }

    @Test
    void nullOverrides_resolveToTheDefault() {
        RateLimitProperties config = new RateLimitProperties();
        config.setOverrides(null);
        RateLimitRuleResolver resolver = new RateLimitRuleResolver(config);

        assertThat(resolver.overrideCount()).isZero();
        assertThat(resolver.resolve(ACME_BILLING_SMS).rule()).isSameAs(config.getDefaultRule());
        assertThat(resolver.resolve(ACME_BILLING_SMS).channelScoped()).isFalse();
    }

    @Test
    void channelScoped_followsTheWinningRule() {
        RateLimitProperties config = new RateLimitProperties();
        config.getDefaultRule().setScope(RateLimitScope.CHANNEL);

        assertThat(new RateLimitRuleResolver(config).resolve(ACME_BILLING_SMS).channelScoped()).isTrue();
    }

    @Test
    void bucketConfiguration_hasOneBandPerBandwidth() {
        RateLimitRule rule = new RateLimitRule();
        rule.setBandwidths(List.of(
                new RateLimitBandwidth(2, 2, Duration.ofMinutes(1)),
                new RateLimitBandwidth(5, 5, Duration.ofHours(1))));

        BucketConfiguration configuration = RateLimitRuleResolver.bucketConfiguration(rule);

        assertThat(configuration.getBandwidths()).hasSize(2);
        assertThat(configuration.getBandwidths()[0].getCapacity()).isEqualTo(2);
        assertThat(configuration.getBandwidths()[1].getCapacity()).isEqualTo(5);
    }

    private static RateLimitOverride override(String tenant, String caller, String channel) {
        RateLimitOverride o = new RateLimitOverride();
        o.setTenant(tenant);
        o.setCaller(caller);
        o.setChannel(channel);
        return o;
    }
}
