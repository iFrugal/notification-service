package com.lazydevs.notification.api.exception;

import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

/**
 * Exception thrown when a template is not found.
 * Carries HTTP 404 Not Found as its status code.
 * <p>
 * Since 1.2.1 it is the {@link TemplateRenderException.Reason#NOT_FOUND} case of
 * {@link TemplateRenderException}; its error code ({@code TEMPLATE_NOT_FOUND}), status
 * code and constructors are unchanged.
 */
@SuppressWarnings("java:S110") // depth comes from the NotificationException tree shipped in 1.2.0
public class TemplateNotFoundException extends TemplateRenderException {

    public TemplateNotFoundException(String message) {
        super(Reason.NOT_FOUND, null, null, null, message, null, HTTP_NOT_FOUND);
    }

    public TemplateNotFoundException(String tenantId, String channel, String notificationType) {
        super(Reason.NOT_FOUND, tenantId, notificationType, null,
                String.format("Template not found for tenant '%s', channel '%s', type '%s'. " +
                        "Expected at: templates/%s/%s/%s.ftl or templates/default/%s/%s.ftl",
                        tenantId, channel, notificationType,
                        tenantId, channel, notificationType,
                        channel, notificationType),
                null, HTTP_NOT_FOUND);
    }
}
