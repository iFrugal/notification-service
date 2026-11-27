package com.lazydevs.notification.channel.whatsapp;

import java.util.Optional;

/**
 * Maps a caller's logical template id onto the WhatsApp template approved at
 * the provider, and names the request variables that fill it (DD-35).
 *
 * <p>Lookup is tenant first, then the {@value #DEFAULT_TENANT} tenant, like the
 * {@code .ftl} lookup of the other channels. The default implementation,
 * {@link PropertiesWhatsAppTemplateRegistry}, reads
 * {@code notification.whatsapp.templates.<tenant>.<templateId>}; declare a bean
 * of this type to read the mapping from somewhere else, for example a database.
 *
 * <p>Implementations must be thread-safe; {@link #find} runs on every send.
 *
 * @since 1.2.2
 */
@FunctionalInterface
public interface WhatsAppTemplateRegistry {

    /** Tenant id whose entries apply to every tenant without its own. */
    String DEFAULT_TENANT = "default";

    /**
     * @param tenantId   the request's tenant, may be {@code null}
     * @param templateId the logical template id ({@code templateId}, else {@code notificationType})
     * @return the binding, empty when neither the tenant nor the default tenant has one
     */
    Optional<WhatsAppTemplateBinding> find(String tenantId, String templateId);
}
