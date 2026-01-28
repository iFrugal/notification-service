package com.lazydevs.notification.api.exception;

import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

/**
 * Exception thrown when a tenant is not found.
 * Returns HTTP 404 Not Found.
 */
public class TenantNotFoundException extends NotificationException {

    public TenantNotFoundException(String tenantId) {
        super("TENANT_NOT_FOUND",
                String.format("Tenant '%s' not found in configuration", tenantId),
                HTTP_NOT_FOUND);
    }
}
