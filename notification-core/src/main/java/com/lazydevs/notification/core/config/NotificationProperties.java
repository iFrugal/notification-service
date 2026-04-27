package com.lazydevs.notification.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for the notification service.
 */
@Data
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    /**
     * Default tenant ID when X-Tenant-Id header is not provided
     */
    private String defaultTenant = "default";

    /**
     * REST API configuration
     */
    private RestProperties rest = new RestProperties();

    /**
     * Kafka configuration
     */
    private KafkaProperties kafka = new KafkaProperties();

    /**
     * Audit configuration
     */
    private AuditProperties audit = new AuditProperties();

    /**
     * Template configuration
     */
    private TemplateProperties template = new TemplateProperties();

    /**
     * Idempotency configuration (see DD-10).
     */
    private IdempotencyProperties idempotency = new IdempotencyProperties();

    /**
     * Caller-registry configuration (see DD-11). Off by default — turning
     * it on populates the admin endpoint and (optionally) enforces strict
     * admission of {@code X-Service-Id} values.
     */
    private CallerRegistryProperties callerRegistry = new CallerRegistryProperties();

    /**
     * Rate-limit configuration (see DD-12). Off by default — turning it
     * on activates the {@code Bucket4jRateLimiter} bean and engages the
     * pre-dispatch token-bucket check inside
     * {@code DefaultNotificationService.send()}.
     */
    private RateLimitProperties rateLimit = new RateLimitProperties();

    /**
     * Tenant-specific configurations
     */
    private Map<String, TenantConfig> tenants = new LinkedHashMap<>();

    @Data
    public static class RestProperties {
        private boolean enabled = true;
        private String basePath = "/api/v1";
    }

    @Data
    public static class KafkaProperties {
        private boolean enabled = false;
        private String topic = "notifications";
        private String groupId = "notification-service";
    }

    @Data
    public static class AuditProperties {
        private boolean enabled = false;
        private boolean storeRequestPayload = false;
        private boolean storeResponsePayload = false;
        private int retentionDays = 90;
        private boolean async = true;
    }

    @Data
    public static class TemplateProperties {
        private String basePath = "classpath:/templates/";
        private boolean cacheEnabled = true;
        private int cacheTtlSeconds = 3600;
    }

    /**
     * Idempotency-key handling. See {@code docs/design-decisions/10-idempotency.md}.
     */
    @Data
    public static class IdempotencyProperties {
        /** Master switch — false disables dedup entirely (the request field is silently ignored). */
        private boolean enabled = true;

        /** TTL after which a key is forgotten and treated as fresh. ISO-8601 duration. */
        private java.time.Duration ttl = java.time.Duration.ofHours(24);

        /**
         * Caffeine bound. Once exceeded, least-recently-used entries are
         * evicted before TTL — operators sizing for high-throughput
         * deployments should raise this. Ignored by Redis-backed stores.
         */
        private long maxEntries = 100_000L;

        /**
         * Backing store. Currently only {@code caffeine} is implemented;
         * a future {@code redis} option is foreseen by DD-10.
         */
        private String store = "caffeine";
    }

    /**
     * Caller-registry handling. See {@code docs/design-decisions/11-caller-identity.md}.
     *
     * <p>Default state ({@code enabled=false}) is the no-op: the
     * {@code X-Service-Id} header is still read into {@code callerId}, but
     * unknown values are accepted silently and the admin endpoint reports
     * the registry as off.
     */
    @Data
    public static class CallerRegistryProperties {
        /** Master switch for caller-registry behaviour. */
        private boolean enabled = false;

        /**
         * If {@code true} <strong>and</strong> {@code enabled=true}, an
         * incoming request whose resolved {@code callerId} is non-null and
         * NOT in {@link #knownServices} is rejected with HTTP 403. A null
         * {@code callerId} (no header sent) is always accepted — strict
         * mode is about admitting only listed callers, not about forcing
         * every caller to identify itself.
         */
        private boolean strict = false;

        /**
         * Advisory list of known caller-ids. When {@link #enabled} is
         * {@code false} this is purely documentation. When {@code true}
         * unknown callers are logged at WARN level (and rejected if
         * {@link #strict}).
         */
        private List<String> knownServices = new ArrayList<>();
    }

    /**
     * Rate-limit handling. See {@code docs/design-decisions/12-rate-limiting.md}.
     *
     * <p>Off by default. When enabled, every request through
     * {@code DefaultNotificationService.send()} consumes a token from the
     * bucket matching the most-specific rule for its
     * {@code (tenantId, callerId, channel)} triple. Buckets are
     * Bucket4j-backed, in-process by default; a future Redis-backed bean
     * will substitute via {@code @ConditionalOnMissingBean}.
     */
    @Data
    public static class RateLimitProperties {
        /** Master switch — false leaves the {@code RateLimiter} bean absent. */
        private boolean enabled = false;

        /**
         * Default rule applied when no override matches the request's
         * {@code (tenantId, callerId, channel)} triple. Generous defaults
         * — operators almost always want to override per-tenant or
         * per-caller; the default is just a backstop that prevents
         * unbounded fan-out.
         */
        private RateLimitRule defaultRule = new RateLimitRule(200, 100, java.time.Duration.ofSeconds(1));

        /**
         * Targeted rule overrides. Match precedence is most-specific-wins:
         * {@code (tenant, caller, channel)} > {@code (tenant, caller)}
         * > {@code (tenant)} > {@code defaultRule}. Within the same
         * specificity, configuration order is the tiebreaker.
         */
        private List<RateLimitOverride> overrides = new ArrayList<>();
    }

    /**
     * A rate-limit rule: bucket capacity, refill amount, and refill period.
     * Bucket4j semantics: at any time the bucket holds at most
     * {@link #capacity} tokens; every {@link #refillPeriod}, up to
     * {@link #refillTokens} are added (greedy refill — they're not held
     * back for a fixed-window boundary).
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RateLimitRule {
        /** Maximum tokens in the bucket — the burst tolerance. */
        private long capacity = 200;

        /** Tokens added each refill period (must be {@code <= capacity}). */
        private long refillTokens = 100;

        /** How often the refill happens. ISO-8601 duration. */
        private java.time.Duration refillPeriod = java.time.Duration.ofSeconds(1);
    }

    /**
     * A targeted override on top of the default rate-limit rule. At least
     * {@link #tenant} must be set; {@link #caller} and {@link #channel}
     * narrow the match further.
     */
    @Data
    public static class RateLimitOverride extends RateLimitRule {
        /** Tenant the rule applies to. Required. */
        private String tenant;

        /**
         * Caller-id the rule narrows to. Optional — leave {@code null} to
         * apply tenant-wide regardless of caller.
         */
        private String caller;

        /**
         * Channel the rule narrows to (e.g. {@code "email"}, {@code "sms"}).
         * Optional — leave {@code null} to apply across all channels.
         * Matched case-insensitively.
         */
        private String channel;
    }

    @Data
    public static class TenantConfig {
        /**
         * Channel configurations for this tenant
         */
        private Map<String, ChannelConfig> channels = new LinkedHashMap<>();
    }

    @Data
    public static class ChannelConfig {
        /**
         * Whether this channel is enabled
         */
        private boolean enabled = true;

        /**
         * Channel-level configuration (shared by all providers)
         */
        private Map<String, Object> config = new LinkedHashMap<>();

        /**
         * Provider-specific configurations
         */
        private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

        /**
         * Check if provider is required in request.
         * Provider is NOT required if:
         * - Only 1 provider is configured, OR
         * - One provider has default=true
         *
         * @return true if provider must be specified in request
         */
        public boolean isProviderRequiredInRequest() {
            if (providers.size() == 1) {
                return false; // Single provider, no need to specify
            }
            // Multiple providers - check if any has default=true
            return providers.values().stream().noneMatch(ProviderConfig::isDefault);
        }

        /**
         * Get the default provider name.
         * Returns:
         * - The only provider if single provider configured
         * - The provider with default=true if multiple providers
         * - null if no default can be determined
         *
         * @return default provider name or null
         */
        public String getDefaultProviderName() {
            if (providers.size() == 1) {
                return providers.keySet().iterator().next();
            }
            // Find provider with default=true
            return providers.entrySet().stream()
                    .filter(e -> e.getValue().isDefault())
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
        }
    }

    @Data
    public static class ProviderConfig {
        /**
         * Spring bean name (for Spring-managed providers)
         */
        private String beanName;

        /**
         * Fully qualified class name (for reflection-based instantiation)
         */
        private String fqcn;

        /**
         * Whether this is the default provider for the channel.
         * Only ONE provider per channel can have default=true.
         * If only one provider is configured, it's automatically the default.
         */
        private boolean isDefault = false;

        /**
         * Provider-specific properties (passed to configure() method)
         */
        private Map<String, Object> properties = new LinkedHashMap<>();
    }
}
