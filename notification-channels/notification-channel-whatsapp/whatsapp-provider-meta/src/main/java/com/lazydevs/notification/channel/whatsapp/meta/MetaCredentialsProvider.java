package com.lazydevs.notification.channel.whatsapp.meta;

/**
 * Supplies the {@link MetaCredentials} for a tenant on every send (DD-35).
 *
 * <p>Declare a Spring bean of this type to read tokens and sender ids from a
 * secret store or a database, for example to give each tenant its own WhatsApp
 * Business Account or to rotate tokens without a restart; cache with your own
 * TTL, since this runs on every send. Without such a bean the provider uses the
 * {@code access-token} and {@code phone-number-id} of the tenant's provider
 * properties.
 *
 * <p>Implementations must be thread-safe. An exception thrown here fails the
 * send with {@code CREDENTIALS_UNAVAILABLE} ({@code TRANSIENT}: nothing was sent).
 *
 * @since 1.3.0
 */
@FunctionalInterface
public interface MetaCredentialsProvider {

    /**
     * @param tenantId the request's tenant
     * @return the credentials to send with, never {@code null}
     */
    MetaCredentials resolve(String tenantId);
}
