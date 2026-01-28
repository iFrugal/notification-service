package com.lazydevs.notification.api.exception;

import com.lazydevs.notification.api.Channel;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;

/**
 * Exception thrown when provider is required in request but not specified.
 * Returns HTTP 400 Bad Request.
 *
 * This happens when:
 * - Multiple providers are configured for a channel, AND
 * - No provider has default=true, AND
 * - The request does not specify which provider to use
 */
public class ProviderRequiredException extends NotificationException {

    public ProviderRequiredException(Channel channel, String tenantId) {
        super("PROVIDER_REQUIRED",
                String.format("Provider must be specified in request for channel '%s' (tenant: '%s'). " +
                        "Multiple providers are configured and none is marked as default.",
                        channel.name().toLowerCase(), tenantId),
                HTTP_BAD_REQUEST);
    }
}
