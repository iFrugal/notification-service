package com.lazydevs.notification.api.channel;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;

import java.util.Map;

/**
 * Base interface for all notification providers.
 * <p>
 * Lifecycle:
 * 1. Instantiate (via beanName or fqcn)
 * 2. configure(properties) - receives YAML properties
 * 3. init() - initialize resources
 * 4. send() - send notifications
 * 5. destroy() - cleanup on shutdown
 */
public interface NotificationProvider {

    /**
     * Get the channel type this provider supports.
     *
     * @return the channel type
     */
    Channel getChannel();

    /**
     * Get the provider name (e.g., "smtp", "ses", "twilio").
     *
     * @return the provider name
     */
    String getProviderName();

    /**
     * Configure the provider with properties from YAML.
     * Called after construction, before init().
     *
     * @param properties configuration properties from YAML
     */
    void configure(Map<String, Object> properties);

    /**
     * Initialize the provider.
     * Called after configure(), before first send().
     * Use for: creating clients, connection pools, validating config.
     */
    default void init() {
        // Default: no-op
    }

    /**
     * Destroy the provider.
     * Called on application shutdown.
     * Use for: closing connections, cleanup resources.
     */
    default void destroy() {
        // Default: no-op
    }

    /**
     * Send a notification.
     *
     * @param request   the notification request
     * @param content   the rendered content (from template engine)
     * @return the send result
     */
    SendResult send(NotificationRequest request, RenderedContent content);

    /**
     * Check if the provider is healthy.
     * Used for health checks.
     *
     * @return true if healthy
     */
    default boolean isHealthy() {
        return true;
    }

    /**
     * Get provider-specific health details.
     *
     * @return health details map
     */
    default Map<String, Object> getHealthDetails() {
        return Map.of("status", isHealthy() ? "UP" : "DOWN");
    }
}
