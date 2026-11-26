package com.lazydevs.notification.core.provider;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.EmailProvider;
import com.lazydevs.notification.api.channel.SmsProvider;
import com.lazydevs.notification.api.channel.WhatsAppProvider;
import com.lazydevs.notification.api.channel.PushProvider;
import com.lazydevs.notification.api.delivery.DeliveryEventEmitter;
import com.lazydevs.notification.api.delivery.DeliveryEventPublisher;
import com.lazydevs.notification.api.exception.ChannelDisabledException;
import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import com.lazydevs.notification.api.exception.ProviderNotFoundException;
import com.lazydevs.notification.api.exception.ProviderRequiredException;
import com.lazydevs.notification.api.exception.TenantNotFoundException;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.ChannelConfig;
import com.lazydevs.notification.core.config.NotificationProperties.ProviderConfig;
import com.lazydevs.notification.core.config.NotificationProperties.TenantConfig;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry for notification providers.
 * Manages provider lifecycle and lookup by tenant/channel/provider.
 *
 * <p>By default every provider of every enabled channel is configured and
 * initialised at startup, and any failure stops the application. With
 * {@code notification.providers.lazy-init=true} (DD-29) a provider is built on
 * the first lookup that needs it instead; with
 * {@code notification.tenant-fallback.enabled=true} (DD-28) a tenant that has
 * no configuration uses the providers of {@code notification.default-tenant}.
 */
@Slf4j
public class ProviderRegistry {

    /**
     * Error code of the send failure reported while a lazily initialised
     * provider cannot be built (DD-29).
     *
     * @since 1.2.1
     */
    public static final String PROVIDER_NOT_CONFIGURED = "PROVIDER_NOT_CONFIGURED";

    /**
     * State of a configured provider that has no instance yet (DD-29).
     *
     * @since 1.2.1
     */
    public enum PendingState {
        /** Lazy initialisation is on and no send has needed the provider yet. */
        NOT_INITIALIZED,
        /** The last attempt to build the provider failed; the next send retries. */
        INIT_FAILED
    }

    private record ProviderKey(String tenantId, Channel channel, String providerName) {
        String display() {
            return tenantId + ":" + channel.name().toLowerCase(Locale.ROOT) + ":" + providerName;
        }
    }

    private final NotificationProperties properties;
    private final ProviderResolver resolver;
    private final DeliveryEventPublisher deliveryEventPublisher;

    /**
     * Cache: tenantId -> channel -> providerName -> provider instance
     */
    private final Map<String, Map<Channel, Map<String, NotificationProvider>>> providerCache =
            new ConcurrentHashMap<>();

    /**
     * All instantiated providers (for lifecycle management). Thread-safe
     * because lazy initialisation adds to it while sends run.
     */
    private final List<NotificationProvider> allProviders = new CopyOnWriteArrayList<>();

    /**
     * Lazy builds in progress, so concurrent first sends share one build.
     * An entry is removed as soon as its build finishes, success or failure.
     */
    private final Map<ProviderKey, CompletableFuture<NotificationProvider>> buildsInFlight =
            new ConcurrentHashMap<>();

    /** Message of the last failed lazy build per provider, until one succeeds. */
    private final Map<ProviderKey, String> failedBuilds = new ConcurrentHashMap<>();

    /**
     * Built-in providers need no registration here: each provider module's
     * auto-configuration registers a prototype bean under the conventional
     * name, and {@link ProviderResolver} finds it.
     */
    public ProviderRegistry(NotificationProperties properties, ProviderResolver resolver) {
        this(properties, resolver, DeliveryEventPublisher.NO_OP);
    }

    /**
     * @param deliveryEventPublisher given to every provider that implements
     *                               {@link DeliveryEventEmitter}, before it is
     *                               configured (DD-25); {@code null} means
     *                               {@link DeliveryEventPublisher#NO_OP}
     * @since 1.2.0
     */
    public ProviderRegistry(NotificationProperties properties, ProviderResolver resolver,
                            DeliveryEventPublisher deliveryEventPublisher) {
        this.properties = properties;
        this.resolver = resolver;
        this.deliveryEventPublisher = deliveryEventPublisher == null
                ? DeliveryEventPublisher.NO_OP
                : deliveryEventPublisher;
    }

    /**
     * Initialize all configured providers at startup.
     * Fails fast if any provider cannot be resolved or configuration is invalid.
     */
    @PostConstruct
    public void initializeProviders() {
        boolean lazy = isLazyInit();
        log.info(lazy ? "Validating notification providers (lazy-init: built on first use)..."
                : "Initializing notification providers...");

        properties.getTenants().forEach((tenantId, tenantConfig) ->
            tenantConfig.getChannels().forEach((channelName, channelConfig) -> {
                if (channelConfig.isEnabled()) {
                    Channel channel = Channel.valueOf(channelName.toUpperCase());

                    // Validate default provider configuration
                    validateDefaultProviderConfig(tenantId, channel, channelConfig);

                    // Initialize providers, unless they are built on first use (DD-29)
                    if (!lazy) {
                        initializeChannelProviders(tenantId, channel, channelConfig);
                    }
                }
            }));

        if (lazy) {
            log.info("{} notification provider(s) will be initialized on first use",
                    getUninitializedProviders().size());
        } else {
            log.info("Initialized {} notification providers", allProviders.size());
        }
    }

    private boolean isLazyInit() {
        return properties.getProviders() != null && properties.getProviders().isLazyInit();
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
                throw new ProviderConfigurationException(
                        String.format("Failed to initialize provider '%s' for channel '%s' in tenant '%s': %s",
                                providerName, channel, tenantId, e.getMessage()), e);
            }
        });
    }

    private NotificationProvider resolveAndInitialize(String tenantId, Channel channel,
                                                       String providerName, ProviderConfig config) {
        Class<? extends NotificationProvider> providerInterface = getProviderInterface(channel);
        NotificationProvider provider = resolver.resolve(
                tenantId, providerName, channel, config, providerInterface);

        // Every resolution path (bean name, class name, built-in name) ends
        // here, so each emitting provider gets the publisher before configure().
        if (provider instanceof DeliveryEventEmitter emitter) {
            emitter.setDeliveryEventPublisher(deliveryEventPublisher);
        }

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
     * A tenant with no configuration uses the default tenant's providers when
     * {@code notification.tenant-fallback.enabled=true} (DD-28). With
     * {@code notification.providers.lazy-init=true} (DD-29) the first lookup of
     * a provider builds it; when the build fails the returned provider's
     * {@code send} fails with {@link #PROVIDER_NOT_CONFIGURED}
     * ({@code TRANSIENT}) and a later lookup tries again.
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
        // Validate tenant exists (or falls back to the default tenant, DD-28)
        String configTenantId = providerTenantId(tenantId);
        if (configTenantId == null) {
            throw new TenantNotFoundException(tenantId);
        }
        TenantConfig tenantConfig = properties.getTenants().get(configTenantId);

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

        ProviderKey key = new ProviderKey(configTenantId, channel, resolvedProviderName);
        NotificationProvider provider = cached(key);
        if (provider != null) {
            return provider;
        }

        // Not built yet: only lazy initialisation builds providers after startup (DD-29)
        ProviderConfig providerConfig = channelConfig.getProviders().get(resolvedProviderName);
        if (!isLazyInit() || providerConfig == null) {
            throw new ProviderNotFoundException(resolvedProviderName, channel.name());
        }
        return buildOnFirstUse(key, providerConfig);
    }

    /**
     * The tenant whose provider configuration serves {@code tenantId}: the
     * tenant itself when it is configured, otherwise the default tenant when
     * the fallback is on and the default tenant is configured (DD-28),
     * otherwise {@code null}.
     */
    private String providerTenantId(String tenantId) {
        Map<String, TenantConfig> tenants = properties.getTenants();
        if (tenants.get(tenantId) != null) {
            return tenantId;
        }
        String defaultTenant = properties.getDefaultTenant();
        boolean fallback = properties.getTenantFallback() != null && properties.getTenantFallback().isEnabled();
        if (fallback && defaultTenant != null && tenants.get(defaultTenant) != null) {
            // The requested tenant id is caller-controlled, so it is not logged.
            log.debug("Unconfigured tenant uses the providers of default tenant '{}'", defaultTenant);
            return defaultTenant;
        }
        return null;
    }

    private NotificationProvider cached(ProviderKey key) {
        Map<Channel, Map<String, NotificationProvider>> tenantProviders = providerCache.get(key.tenantId());
        if (tenantProviders == null) {
            return null;
        }
        Map<String, NotificationProvider> channelProviders = tenantProviders.get(key.channel());
        return channelProviders == null ? null : channelProviders.get(key.providerName());
    }

    /**
     * Build a provider on its first use (DD-29). Concurrent callers for the
     * same provider share one build. A successful build is cached like an
     * eager one; a failed build is not, and the caller gets an
     * {@link UnavailableProvider} whose send fails with
     * {@link #PROVIDER_NOT_CONFIGURED} as a {@code TRANSIENT} failure.
     */
    private NotificationProvider buildOnFirstUse(ProviderKey key, ProviderConfig providerConfig) {
        CompletableFuture<NotificationProvider> mine = new CompletableFuture<>();
        CompletableFuture<NotificationProvider> running = buildsInFlight.putIfAbsent(key, mine);
        if (running == null) {
            running = mine;
            try {
                // A build that finished between our cache miss and putIfAbsent is reused.
                NotificationProvider provider = cached(key);
                mine.complete(provider != null ? provider : build(key, providerConfig));
            } catch (RuntimeException e) {
                recordFailedBuild(key, e);
                mine.completeExceptionally(e);
            } finally {
                buildsInFlight.remove(key, mine);
            }
        }
        try {
            return running.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String reason = String.format("Provider '%s' for channel %s is not configured: %s",
                    key.providerName(), key.channel(), cause.getMessage());
            return new UnavailableProvider(key.channel(), key.providerName(), reason,
                    () -> buildOnFirstUse(key, providerConfig));
        }
    }

    private NotificationProvider build(ProviderKey key, ProviderConfig providerConfig) {
        NotificationProvider provider = resolveAndInitialize(
                key.tenantId(), key.channel(), key.providerName(), providerConfig);
        cacheProvider(key.tenantId(), key.channel(), key.providerName(), provider);
        allProviders.add(provider);
        failedBuilds.remove(key);
        log.info("Initialized provider on first use: tenant={}, channel={}, provider={}",
                sanitize(key.tenantId()), key.channel(), sanitize(key.providerName()));
        return provider;
    }

    /**
     * Log a failed lazy build at WARN when its message is new, and at DEBUG
     * when it repeats the last one, so a misconfigured provider does not log
     * a warning per send.
     */
    private void recordFailedBuild(ProviderKey key, RuntimeException e) {
        String message = String.valueOf(e.getMessage());
        String previous = failedBuilds.put(key, message);
        if (message.equals(previous)) {
            log.debug("Provider {} still cannot be initialized: {}", sanitize(key.display()), sanitize(message));
        } else {
            log.warn("Provider {} could not be initialized; its sends fail with {} until it can: {}",
                    sanitize(key.display()), PROVIDER_NOT_CONFIGURED, sanitize(message));
        }
    }

    /**
     * Check if provider is required in request for a specific tenant/channel.
     * A tenant that falls back to the default tenant (DD-28) answers for the
     * default tenant's channel.
     */
    public boolean isProviderRequiredInRequest(String tenantId, Channel channel) {
        String configTenantId = providerTenantId(tenantId);
        if (configTenantId == null) {
            return true;
        }

        ChannelConfig channelConfig = properties.getTenants().get(configTenantId)
                .getChannels().get(channel.name().toLowerCase());
        if (channelConfig == null) {
            return true;
        }

        return channelConfig.isProviderRequiredInRequest();
    }

    /**
     * Get all providers for health checks: the initialised instances, keyed
     * {@code tenant:channel:provider} by the configured tenant that owns them.
     * Tenants served by the default-tenant fallback never appear.
     */
    public Map<String, NotificationProvider> getAllProviders() {
        Map<String, NotificationProvider> result = new LinkedHashMap<>();
        providerCache.forEach((tenantId, channels) ->
            channels.forEach((channel, providers) ->
                providers.forEach((name, provider) ->
                    result.put(new ProviderKey(tenantId, channel, name).display(), provider))));
        return result;
    }

    /**
     * Providers configured on an enabled channel that have no instance yet,
     * keyed like {@link #getAllProviders()}. Always empty unless
     * {@code notification.providers.lazy-init=true} (DD-29).
     *
     * @since 1.2.1
     */
    public Map<String, PendingState> getUninitializedProviders() {
        Map<String, PendingState> result = new LinkedHashMap<>();
        properties.getTenants().forEach((tenantId, tenantConfig) ->
            tenantConfig.getChannels().forEach((channelName, channelConfig) -> {
                if (channelConfig.isEnabled()) {
                    Channel channel = Channel.valueOf(channelName.toUpperCase());
                    channelConfig.getProviders().keySet().forEach(name ->
                        addIfPending(result, new ProviderKey(tenantId, channel, name)));
                }
            }));
        return result;
    }

    private void addIfPending(Map<String, PendingState> result, ProviderKey key) {
        if (cached(key) == null) {
            result.put(key.display(),
                    failedBuilds.containsKey(key) ? PendingState.INIT_FAILED : PendingState.NOT_INITIALIZED);
        }
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
        failedBuilds.clear();
    }

    private Class<? extends NotificationProvider> getProviderInterface(Channel channel) {
        return switch (channel) {
            case EMAIL -> EmailProvider.class;
            case SMS -> SmsProvider.class;
            case WHATSAPP -> WhatsAppProvider.class;
            case PUSH -> PushProvider.class;
        };
    }

    /**
     * The value with control characters and line breaks replaced by {@code _}, so a
     * caller- or provider-supplied value cannot forge log lines; {@code "null"} for null.
     */
    private static String sanitize(String s) {
        return s == null ? "null" : s.replaceAll("\\p{Cntrl}", "_").replaceAll("\\R", "_");
    }
}
