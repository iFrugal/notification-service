package com.lazydevs.notification.api.exception;

import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

/**
 * Exception thrown when a requested provider is not found.
 * Returns HTTP 404 Not Found.
 */
public class ProviderNotFoundException extends NotificationException {

    public ProviderNotFoundException(String message) {
        super("PROVIDER_NOT_FOUND", message, HTTP_NOT_FOUND);
    }

    public ProviderNotFoundException(String providerName, String channel) {
        super("PROVIDER_NOT_FOUND",
                String.format("Provider '%s' not found for channel '%s'. " +
                        "For external providers, specify 'beanName' or 'fqcn' in configuration.",
                        providerName, channel),
                HTTP_NOT_FOUND);
    }
}
