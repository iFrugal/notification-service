package com.lazydevs.notification.api.exception;

import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

/**
 * Exception thrown when a template is not found.
 * Returns HTTP 404 Not Found.
 */
public class TemplateNotFoundException extends NotificationException {

    public TemplateNotFoundException(String message) {
        super("TEMPLATE_NOT_FOUND", message, HTTP_NOT_FOUND);
    }

    public TemplateNotFoundException(String tenantId, String channel, String notificationType) {
        super("TEMPLATE_NOT_FOUND",
                String.format("Template not found for tenant '%s', channel '%s', type '%s'. " +
                        "Expected at: templates/%s/%s/%s.ftl or templates/default/%s/%s.ftl",
                        tenantId, channel, notificationType,
                        tenantId, channel, notificationType,
                        channel, notificationType),
                HTTP_NOT_FOUND);
    }
}
