package com.lazydevs.notification.api.exception;

import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;

/**
 * Exception thrown when provider configuration is invalid.
 * Returns HTTP 500 Internal Server Error (configuration issue is a server problem).
 */
public class ProviderConfigurationException extends NotificationException {

    /** Error code surfaced on every provider-configuration failure. */
    private static final String ERROR_CODE = "PROVIDER_CONFIGURATION_ERROR";

    public ProviderConfigurationException(String message) {
        super(ERROR_CODE, message, HTTP_INTERNAL_ERROR);
    }

    public ProviderConfigurationException(String message, Throwable cause) {
        super(ERROR_CODE, message, cause);
    }

    public ProviderConfigurationException(String providerName, String channel, String reason) {
        super(ERROR_CODE,
                String.format("Failed to configure provider '%s' for channel '%s': %s",
                        providerName, channel, reason),
                HTTP_INTERNAL_ERROR);
    }
}
