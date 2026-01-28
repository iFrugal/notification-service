package com.lazydevs.notification.core.provider;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.EmailProvider;
import com.lazydevs.notification.api.channel.SmsProvider;
import com.lazydevs.notification.api.channel.WhatsAppProvider;
import com.lazydevs.notification.api.channel.PushProvider;
import com.lazydevs.notification.api.exception.ChannelDisabledException;
import com.lazydevs.notification.api.exception.ProviderNotFoundException;
import com.lazydevs.notification.api.exception.ProviderRequiredException;
import com.lazydevs.notification.api.exception.TenantNotFoundException;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.ChannelConfig;
import com.lazydevs.notification.core.config.NotificationProperties.ProviderConfig;
import com.lazydevs.notification.core.config.NotificationProperties.TenantConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for notification providers.
 * Manages provider lifecycle and lookup by tenant/channel/provider.
 */
@Slf4j
@Component
public class ProviderRegistry {

    private final NotificationProperties properties;
    private final ProviderResolver resolver;

    /**
     * Cache: tenantId -> channel -> providerName -> provider instance
     */
    private final Map<String, Map<Channel, Map<String, NotificationProvider>>> providerCache =
            new ConcurrentHashMap<>();

    /**
     * All instantiated providers (for lifecycle management)
     */
    private final List<NotificationProvider> allProviders = new ArrayList<>();

    /**
     * Built-in provider classes (channel:name -> class)
     */
    private final Map<String, Class<? extends NotificationProvider>> builtInProviders = new HashMap<>();

    public ProviderRegistry(NotificationProperties properties, ProviderResolver resolver) {
        this.properties = properties;
        this.resolver = resolver;
        registerBuiltInProviders();
    }

    /**
     * Register built-in provider mappings.
     * These are providers that come with the notification-service.
     */
    private void registerBuiltInProviders() {
        // Email providers
        registerBuiltIn(Channel.EMAIL, "smtp", "com.lazydevs.notification.channel.email.smtp.SmtpEmailProvider");
        registerBuiltIn(Channel.EMAIL, "ses", "com.lazydevs.notification.channel.email.ses.SesEmailProvider");

        // SMS providers
        registerBuiltIn(Channel.SMS, "twilio", "com.lazydevs.notification.channel.sms.twilio.TwilioSmsProvider");
        registerBuiltIn(Channel.SMS, "sns", "com.lazydevs.notification.channel.sms.sns.SnsSmsProvider");

        // WhatsApp providers
        registerBuiltIn(Channel.WHATSAPP, "twilio", "com.lazydevs.notification.channel.whatsapp.twilio.TwilioWhatsAppProvider");
        registerBuiltIn(Channel.WHATSAPP, "meta", "com.lazydevs.notification.channel.whatsapp.meta.MetaWhatsAppProvider");

        // Push providers
        registerBuiltIn(Channel.PUSH, "fcm", "com.lazydevs.notification.channel.push.fcm.FcmPushProvider");
        registerBuiltIn(Channel.PUSH, "apns", "com.lazydevs.notification.channel.push.apns.ApnsPushProvider");
    }

    @SuppressWarnings("unchecked")
    private void registerBuiltIn(Channel channel, String name, String className) {
        try {
            Class<?> clazz = Class.forName(className);
            builtInProviders.put(channel.name() + ":" + name, (Class<? extends NotificationProvider>) clazz);
            log.debug("Registered built-in provider: {}:{} -> {}", channel, name, className);
        } catch (ClassNotFoundException e) {
            // Provider module not on classpath - this is OK
            log.debug("Built-in provider not available (module not on classpath): {}:{}", channel, name);
        }
    }

    /**
     * Initialize all configured providers at startup.
     * Fails fast if any provider cannot be resolved or configuration is invalid.
     */
    @PostConstruct
    public void initializeProviders() {
        log.info("Initializing notification providers...");

        properties.getTenants().forEach((tenantId, tenantConfig) -> {
            tenantConfig.getChannels().forEach((channelName, channelConfig) -> {
                if (channelConfig.isEnabled()) {
                    Channel channel = Channel.valueOf(channelName.toUpperCase());

                    // Validate default provider configuration
                    validateDefaultProviderConfig(tenantId, channel, channelConfig);

                    // Initialize providers
                    initializeChannelProviders(tenantId, channel, channelConfig);
                }
            });
        });

        log.info("Initialized {} notification providers", allProviders.size());
    }

    /**
     * Validate that default provider configuration is correct.
     * - If multiple providers: exactly ONE must have default=true
     * - If single provider: no validation needed (it's auto-default)
     */
    private void validateDefaultProviderConfig(String tenantId, Channel channel, ChannelConfig channelConfig) {
        Map<String, ProviderConfig> providers = channelConfig.getProviders();

        if (providers.size() <= 1) {
            // Single or no provider - no validation needed
            return;
        }

        // Count providers with default=true
        long defaultCount = providers.values().stream()
                .filter(ProviderConfig::isDefault)
                .count();

        if (defaultCount > 1) {
            // Multiple defaults - fail startup
            List<String> defaultProviders = providers.entrySet().stream()
                    .filter(e -> e.getValue().isDefault())
                    .map(Map.Entry::getKey)
                    .toList();

            throw new RuntimeException(String.format(
                    "Invalid configuration for tenant '%s', channel '%s': " +
                            "Multiple providers have 'default: true' (%s). Only ONE provider can be the default.",
                    tenantId, channel.name().toLowerCase(), String.join(", ", defaultProviders)));
        }

        // If no default is set, log a warning - provider will be required in requests
        if (defaultCount == 0) {
            log.warn("Tenant '{}', channel '{}': Multiple providers configured ({}) but none has 'default: true'. " +
                            "Provider must be specified in every request.",
                    tenantId, channel.name().toLowerCase(),
                    String.join(", ", providers.keySet()));
        }
    }

    private void initializeChannelProviders(String tenantId, Channel channel, ChannelConfig channelConfig) {
        channelConfig.getProviders().forEach((providerName, providerConfig) -> {
            try {
                NotificationProvider provider = resolveAndInitialize(
                        tenantId, channel, providerName, providerConfig);
                cacheProvider(tenantId, channel, providerName, provider);
                allProviders.add(provider);

                String defaultMarker = providerConfig.isDefault() ? " [DEFAULT]" : "";
                if (channelConfig.getProviders().size() == 1) {
                    defaultMarker = " [AUTO-DEFAULT]";
                }
                log.info("Initialized provider: tenant={}, channel={}, provider={}{}",
                        tenantId, channel, providerName, defaultMarker);
            } catch (Exception e) {
                throw new RuntimeException(
                        String.format("Failed to initialize provider '%s' for channel '%s' in tenant '%s': %s",
                                providerName, channel, tenantId, e.getMessage()), e);
            }
        });
    }

    private NotificationProvider resolveAndInitialize(String tenantId, Channel channel,
                                                       String providerName, ProviderConfig config) {
        Class<? extends NotificationProvider> providerInterface = getProviderInterface(channel);
        NotificationProvider provider = resolver.resolve(
                providerName, channel, config, providerInterface, builtInProviders);

        // Configure with merged properties (channel config + provider config)
        Map<String, Object> mergedConfig = mergeConfig(tenantId, channel, config);
        provider.configure(mergedConfig);

        // Initialize
        provider.init();

        return provider;
    }

    private Map<String, Object> mergeConfig(String tenantId, Channel channel, ProviderConfig providerConfig) {
        Map<String, Object> merged = new LinkedHashMap<>();

        // Add channel-level config
        TenantConfig tenantConfig = properties.getTenants().get(tenantId);
        if (tenantConfig != null) {
            ChannelConfig channelConfig = tenantConfig.getChannels().get(channel.name().toLowerCase());
            if (channelConfig != null && channelConfig.getConfig() != null) {
                merged.putAll(channelConfig.getConfig());
            }
        }

        // Add provider-specific config (overrides channel config)
        if (providerConfig.getProperties() != null) {
            merged.putAll(providerConfig.getProperties());
        }

        return merged;
    }

    private void cacheProvider(String tenantId, Channel channel, String providerName,
                                NotificationProvider provider) {
        providerCache
                .computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(channel, k -> new ConcurrentHashMap<>())
                .put(providerName, provider);
    }

    /**
     * Get a provider for the given tenant, channel, and optional provider name.
     *
     * Provider resolution:
     * 1. If providerName is specified in request -> use that provider
     * 2. If only 1 provider configured -> use that (auto-default)
     * 3. If multiple providers and one has default=true -> use that
     * 4. If multiple providers and none has default=true -> throw ProviderRequiredException
     *
     * @param tenantId     The tenant ID
     * @param channel      The notification channel
     * @param providerName Optional provider name (can be null)
     * @return The resolved provider instance
     * @throws TenantNotFoundException      if tenant doesn't exist
     * @throws ChannelDisabledException     if channel is disabled for tenant
     * @throws ProviderRequiredException    if provider is required but not specified
     * @throws ProviderNotFoundException    if specified provider doesn't exist
     */
    public NotificationProvider getProvider(String tenantId, Channel channel, String providerName) {
        // Validate tenant exists
        TenantConfig tenantConfig = properties.getTenants().get(tenantId);
        if (tenantConfig == null) {
            throw new TenantNotFoundException(tenantId);
        }

        // Validate channel is enabled
        ChannelConfig channelConfig = tenantConfig.getChannels().get(channel.name().toLowerCase());
        if (channelConfig == null || !channelConfig.isEnabled()) {
            throw new ChannelDisabledException(channel, tenantId);
        }

        // Determine provider name
        String resolvedProviderName = providerName;
        if (resolvedProviderName == null || resolvedProviderName.isBlank()) {
            // No provider specified in request - resolve default
            resolvedProviderName = channelConfig.getDefaultProviderName();

            if (resolvedProviderName == null) {
                // No default could be determined - provider is required
                throw new ProviderRequiredException(channel, tenantId);
            }
        }

        // Get from cache
        Map<Channel, Map<String, NotificationProvider>> tenantProviders = providerCache.get(tenantId);
        if (tenantProviders == null) {
            throw new ProviderNotFoundException(resolvedProviderName, channel.name());
        }

        Map<String, NotificationProvider> channelProviders = tenantProviders.get(channel);
        if (channelProviders == null) {
            throw new ProviderNotFoundException(resolvedProviderName, channel.name());
        }

        NotificationProvider provider = channelProviders.get(resolvedProviderName);
        if (provider == null) {
            throw new ProviderNotFoundException(resolvedProviderName, channel.name());
        }

        return provider;
    }

    /**
     * Check if provider is required in request for a specific tenant/channel.
     */
    public boolean isProviderRequiredInRequest(String tenantId, Channel channel) {
        TenantConfig tenantConfig = properties.getTenants().get(tenantId);
        if (tenantConfig == null) {
            return true;
        }

        ChannelConfig channelConfig = tenantConfig.getChannels().get(channel.name().toLowerCase());
        if (channelConfig == null) {
            return true;
        }

        return channelConfig.isProviderRequiredInRequest();
    }

    /**
     * Get all providers for health checks.
     */
    public Map<String, NotificationProvider> getAllProviders() {
        Map<String, NotificationProvider> result = new LinkedHashMap<>();
        providerCache.forEach((tenantId, channels) -> {
            channels.forEach((channel, providers) -> {
                providers.forEach((name, provider) -> {
                    String key = tenantId + ":" + channel.name().toLowerCase() + ":" + name;
                    result.put(key, provider);
                });
            });
        });
        return result;
    }

    /**
     * Destroy all providers on shutdown.
     */
    @PreDestroy
    public void destroyProviders() {
        log.info("Destroying notification providers...");
        for (NotificationProvider provider : allProviders) {
            try {
                provider.destroy();
                log.debug("Destroyed provider: {}", provider.getProviderName());
            } catch (Exception e) {
                log.warn("Error destroying provider {}: {}", provider.getProviderName(), e.getMessage());
            }
        }
        providerCache.clear();
        allProviders.clear();
    }

    @SuppressWarnings("unchecked")
    private Class<? extends NotificationProvider> getProviderInterface(Channel channel) {
        return switch (channel) {
            case EMAIL -> (Class) EmailProvider.class;
            case SMS -> (Class) SmsProvider.class;
            case WHATSAPP -> (Class) WhatsAppProvider.class;
            case PUSH -> (Class) PushProvider.class;
        };
    }
}
