package com.lazydevs.notification.api.exception;

import com.lazydevs.notification.api.Channel;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;

/**
 * Exception thrown when a channel is disabled for a tenant.
 * Returns HTTP 400 Bad Request.
 */
public class ChannelDisabledException extends NotificationException {

    public ChannelDisabledException(String message) {
        super("CHANNEL_DISABLED", message, HTTP_BAD_REQUEST);
    }

    public ChannelDisabledException(Channel channel, String tenantId) {
        super("CHANNEL_DISABLED",
                String.format("Channel '%s' is disabled for tenant '%s'", channel, tenantId),
                HTTP_BAD_REQUEST);
    }
}
