package com.lazydevs.notification.api.exception;

import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;

/**
 * Exception thrown when provider configuration is invalid.
 * Returns HTTP 500 Internal Server Error (configuration issue is a server problem).
 */
public class ProviderConfigurationException extends NotificationException {

    public ProviderConfigurationException(String message) {
        super("PROVIDER_CONFIGURATION_ERROR", message, HTTP_INTERNAL_ERROR);
    }

    public ProviderConfigurationException(String message, Throwable cause) {
        super("PROVIDER_CONFIGURATION_ERROR", message, cause);
    }

    public ProviderConfigurationException(String providerName, String channel, String reason) {
        super("PROVIDER_CONFIGURATION_ERROR",
                String.format("Failed to configure provider '%s' for channel '%s': %s",
                        providerName, channel, reason),
                HTTP_INTERNAL_ERROR);
    }
}
