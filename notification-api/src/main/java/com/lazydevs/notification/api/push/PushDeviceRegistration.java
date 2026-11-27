package com.lazydevs.notification.api.push;

import java.util.Map;
import java.util.Objects;

/**
 * A device an app registers for push (DD-37): the input of
 * {@link PushDeviceRegistry#register(PushDeviceRegistration)}.
 *
 * <p>String values are stripped; blank optional values become {@code null}.
 * {@link #toString()} never shows the token, only its {@link PushTokens#hash(String) hash}.
 *
 * @param tenantId       tenant scope of the device; {@code null} uses the current
 *                       {@code TenantContext} tenant, and without one the pool (no tenant) scope
 * @param userId         the user the device belongs to, required, at most 255 characters,
 *                       no control characters
 * @param installationId the app installation, stable across token rotation, required,
 *                       at most 255 characters, no control characters
 * @param appId          the app, such as a bundle id, for an account with several apps; may be {@code null}
 * @param platform       the platform, required
 * @param token          the push token (FCM registration token), required, at most 4096 characters
 * @param locale         BCP-47 locale of the device, may be {@code null}
 * @param timezone       IANA time zone of the device, may be {@code null}
 * @param attributes     free-form string attributes, such as the app version; never {@code null} after construction
 * @since 1.2.3
 */
public record PushDeviceRegistration(
        String tenantId,
        String userId,
        String installationId,
        String appId,
        PushPlatform platform,
        String token,
        String locale,
        String timezone,
        Map<String, String> attributes) {

    public PushDeviceRegistration {
        tenantId = PushFields.optional("tenantId", tenantId, PushFields.MAX_ID_LENGTH);
        userId = PushFields.identifier("userId", userId, PushFields.MAX_ID_LENGTH);
        installationId = PushFields.identifier("installationId", installationId, PushFields.MAX_ID_LENGTH);
        appId = PushFields.optional("appId", appId, PushFields.MAX_ID_LENGTH);
        Objects.requireNonNull(platform, "platform");
        token = PushFields.required("token", token, PushFields.MAX_TOKEN_LENGTH);
        locale = PushFields.optional("locale", locale, PushFields.MAX_LOCALE_LENGTH);
        timezone = PushFields.optional("timezone", timezone, PushFields.MAX_TIMEZONE_LENGTH);
        attributes = PushFields.attributes(attributes);
    }

    /**
     * A registration with only the required values, in the current tenant scope.
     *
     * @param userId         the user
     * @param installationId the app installation
     * @param platform       the platform
     * @param token          the push token
     * @return the registration
     */
    public static PushDeviceRegistration of(String userId, String installationId, PushPlatform platform,
                                            String token) {
        return new PushDeviceRegistration(null, userId, installationId, null, platform, token, null, null, null);
    }

    /** @return the {@link PushTokens#hash(String) hash} of the token */
    public String tokenHash() {
        return PushTokens.hash(token);
    }

    /** Shows the token hash, never the token. */
    @Override
    public String toString() {
        return "PushDeviceRegistration[tenantId=" + tenantId + ", userId=" + userId
                + ", installationId=" + installationId + ", appId=" + appId + ", platform=" + platform
                + ", tokenHash=" + tokenHash() + ", locale=" + locale + ", timezone=" + timezone
                + ", attributes=" + attributes + "]";
    }
}
