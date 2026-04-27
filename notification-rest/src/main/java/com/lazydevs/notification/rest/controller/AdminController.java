package com.lazydevs.notification.rest.controller;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.caller.CallerRegistry;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.ChannelConfig;
import com.lazydevs.notification.core.config.NotificationProperties.ProviderConfig;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitOverride;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitProperties;
import com.lazydevs.notification.core.config.NotificationProperties.TenantConfig;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.ratelimit.Bucket4jRateLimiter;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller for admin/configuration endpoints.
 */
@Slf4j
@RestController
@RequestMapping("${notification.rest.base-path:/api/v1}/admin")
@ConditionalOnProperty(prefix = "notification.rest", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AdminController {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "secret", "token", "api-key", "apikey", "auth-token", "authtoken",
            "credentials", "private-key", "privatekey", "account-sid", "accountsid",
            "access-key", "accesskey", "secret-key", "secretkey"
    );

    private final NotificationProperties properties;
    private final ProviderRegistry providerRegistry;
    private final NotificationTemplateEngine templateEngine;
    private final CallerRegistry callerRegistry;
    private final java.util.Optional<RateLimiter> rateLimiter;

    public AdminController(NotificationProperties properties,
                           ProviderRegistry providerRegistry,
                           NotificationTemplateEngine templateEngine,
                           CallerRegistry callerRegistry,
                           java.util.Optional<RateLimiter> rateLimiter) {
        this.properties = properties;
        this.providerRegistry = providerRegistry;
        this.templateEngine = templateEngine;
        this.callerRegistry = callerRegistry;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Get full configuration for all tenants.
     */
    @GetMapping("/configuration")
    public ResponseEntity<Map<String, Object>> getConfiguration() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("defaultTenant", properties.getDefaultTenant());
        result.put("tenants", buildTenantsConfig());
        return ResponseEntity.ok(result);
    }

    /**
     * Get configuration for a specific tenant.
     */
    @GetMapping("/configuration/tenants/{tenantId}")
    public ResponseEntity<Map<String, Object>> getTenantConfiguration(@PathVariable String tenantId) {
        TenantConfig tenantConfig = properties.getTenants().get(tenantId);
        if (tenantConfig == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(buildTenantConfig(tenantId, tenantConfig));
    }

    /**
     * Get configuration for a specific channel in a tenant.
     */
    @GetMapping("/configuration/tenants/{tenantId}/channels/{channel}")
    public ResponseEntity<Map<String, Object>> getChannelConfiguration(
            @PathVariable String tenantId,
            @PathVariable String channel) {

        TenantConfig tenantConfig = properties.getTenants().get(tenantId);
        if (tenantConfig == null) {
            return ResponseEntity.notFound().build();
        }

        ChannelConfig channelConfig = tenantConfig.getChannels().get(channel.toLowerCase());
        if (channelConfig == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(buildChannelConfig(channel, channelConfig));
    }

    /**
     * Health check with provider status.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Overall status
        boolean allHealthy = true;
        Map<String, String> providerStatus = new LinkedHashMap<>();

        for (Map.Entry<String, NotificationProvider> entry : providerRegistry.getAllProviders().entrySet()) {
            String key = entry.getKey();
            NotificationProvider provider = entry.getValue();
            boolean healthy = provider.isHealthy();
            providerStatus.put(key, healthy ? "HEALTHY" : "UNHEALTHY");
            if (!healthy) {
                allHealthy = false;
            }
        }

        result.put("status", allHealthy ? "UP" : "DEGRADED");
        result.put("providers", providerStatus);

        // Kafka status (if enabled)
        if (properties.getKafka().isEnabled()) {
            result.put("kafka", Map.of(
                    "enabled", true,
                    "topic", properties.getKafka().getTopic(),
                    "groupId", properties.getKafka().getGroupId()
            ));
        }

        // Audit status
        result.put("audit", Map.of("enabled", properties.getAudit().isEnabled()));

        return ResponseEntity.ok(result);
    }

    /**
     * Caller-registry state (DD-11). Mirrors the configuration the
     * registry was initialised with — useful during rollout to confirm the
     * deployed pod sees the expected list and mode.
     */
    @GetMapping("/caller-registry")
    public ResponseEntity<Map<String, Object>> getCallerRegistry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", callerRegistry.isEnabled());
        result.put("strict", callerRegistry.isStrict());
        // Sorted-by-config-order set; Set.copyOf on a LinkedHashSet
        // preserves the iteration order, so ArrayList.sort is unnecessary.
        result.put("knownServices", new java.util.ArrayList<>(callerRegistry.getKnownServices()));
        return ResponseEntity.ok(result);
    }

    /**
     * Rate-limit configuration + live bucket snapshot (DD-12). Returns
     * the configured default rule, all overrides, and (when the in-memory
     * Bucket4j impl is active) the per-bucket available-token counts.
     */
    @GetMapping("/rate-limit")
    public ResponseEntity<Map<String, Object>> getRateLimit() {
        Map<String, Object> result = new LinkedHashMap<>();
        RateLimitProperties cfg = properties.getRateLimit();
        result.put("enabled", cfg.isEnabled());
        result.put("default", ruleAsMap(cfg.getDefaultRule()));

        java.util.List<Map<String, Object>> overrides = new java.util.ArrayList<>();
        for (RateLimitOverride o : cfg.getOverrides()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("tenant", o.getTenant());
            if (o.getCaller() != null) entry.put("caller", o.getCaller());
            if (o.getChannel() != null) entry.put("channel", o.getChannel());
            entry.put("capacity", o.getCapacity());
            entry.put("refillTokens", o.getRefillTokens());
            entry.put("refillPeriod", o.getRefillPeriod().toString());
            overrides.add(entry);
        }
        result.put("overrides", overrides);

        // Live snapshot — only available when the default Bucket4j impl is
        // wired (a future Redis impl would expose this differently or not
        // at all).
        if (rateLimiter.isPresent() && rateLimiter.get() instanceof Bucket4jRateLimiter b4j) {
            java.util.List<Map<String, Object>> active = new java.util.ArrayList<>();
            b4j.snapshot().forEach((key, tokens) -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("tenant", key.tenantId());
                e.put("caller", key.callerId());
                e.put("channel", key.channel());
                e.put("availableTokens", tokens);
                active.add(e);
            });
            result.put("activeBuckets", active);
        }
        return ResponseEntity.ok(result);
    }

    private static Map<String, Object> ruleAsMap(NotificationProperties.RateLimitRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capacity", r.getCapacity());
        m.put("refillTokens", r.getRefillTokens());
        m.put("refillPeriod", r.getRefillPeriod().toString());
        return m;
    }

    /**
     * Clear template cache for a tenant.
     */
    @PostMapping("/cache/templates/clear")
    public ResponseEntity<Map<String, String>> clearTemplateCache(
            @RequestParam(required = false) String tenantId) {

        if (tenantId != null) {
            templateEngine.clearCache(tenantId);
            return ResponseEntity.ok(Map.of("message", "Template cache cleared for tenant: " + tenantId));
        } else {
            templateEngine.clearAllCache();
            return ResponseEntity.ok(Map.of("message", "All template caches cleared"));
        }
    }

    // ========== Helper Methods ==========

    private Map<String, Object> buildTenantsConfig() {
        Map<String, Object> tenants = new LinkedHashMap<>();
        properties.getTenants().forEach((tenantId, tenantConfig) -> {
            tenants.put(tenantId, buildTenantConfig(tenantId, tenantConfig));
        });
        return tenants;
    }

    private Map<String, Object> buildTenantConfig(String tenantId, TenantConfig tenantConfig) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> channels = new LinkedHashMap<>();

        // Include all channels (enabled and disabled)
        for (Channel channel : Channel.values()) {
            String channelName = channel.name().toLowerCase();
            ChannelConfig channelConfig = tenantConfig.getChannels().get(channelName);

            if (channelConfig != null) {
                channels.put(channelName, buildChannelConfig(channelName, channelConfig));
            } else {
                // Channel not configured
                channels.put(channelName, Map.of("enabled", false, "providers", Map.of()));
            }
        }

        result.put("channels", channels);
        return result;
    }

    private Map<String, Object> buildChannelConfig(String channelName, ChannelConfig channelConfig) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", channelConfig.isEnabled());

        // Show default provider info
        String defaultProviderName = channelConfig.getDefaultProviderName();
        result.put("defaultProvider", defaultProviderName);
        result.put("providerRequiredInRequest", channelConfig.isProviderRequiredInRequest());

        if (channelConfig.getConfig() != null && !channelConfig.getConfig().isEmpty()) {
            result.put("config", maskSensitive(channelConfig.getConfig()));
        }

        Map<String, Object> providers = new LinkedHashMap<>();
        channelConfig.getProviders().forEach((providerName, providerConfig) -> {
            boolean isAutoDefault = channelConfig.getProviders().size() == 1;
            providers.put(providerName, buildProviderConfig(providerConfig, isAutoDefault));
        });
        result.put("providers", providers);

        return result;
    }

    private Map<String, Object> buildProviderConfig(ProviderConfig providerConfig, boolean isAutoDefault) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Determine provider type
        if (providerConfig.getBeanName() != null) {
            result.put("type", "SPRING_BEAN");
            result.put("beanName", providerConfig.getBeanName());
        } else if (providerConfig.getFqcn() != null) {
            result.put("type", "FQCN");
            result.put("fqcn", providerConfig.getFqcn());
        } else {
            result.put("type", "BUILT_IN");
        }

        result.put("status", "ACTIVE");

        // Show default status
        if (isAutoDefault) {
            result.put("isDefault", true);
            result.put("defaultReason", "AUTO (single provider)");
        } else if (providerConfig.isDefault()) {
            result.put("isDefault", true);
            result.put("defaultReason", "CONFIGURED (default: true)");
        } else {
            result.put("isDefault", false);
        }

        // Mask sensitive config values
        if (providerConfig.getProperties() != null && !providerConfig.getProperties().isEmpty()) {
            result.put("config", maskSensitive(providerConfig.getProperties()));
        }

        return result;
    }

    /**
     * Mask sensitive values in configuration.
     */
    private Map<String, Object> maskSensitive(Map<String, Object> config) {
        Map<String, Object> masked = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            if (isSensitive(key)) {
                masked.put(key, "***MASKED***");
            } else if (value instanceof Map) {
                masked.put(key, maskSensitive((Map<String, Object>) value));
            } else {
                masked.put(key, value);
            }
        });
        return masked;
    }

    private boolean isSensitive(String key) {
        String lower = key.toLowerCase().replace("-", "").replace("_", "");
        return SENSITIVE_KEYS.stream().anyMatch(s -> lower.contains(s.replace("-", "")));
    }
}
