package com.lazydevs.notification.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
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
