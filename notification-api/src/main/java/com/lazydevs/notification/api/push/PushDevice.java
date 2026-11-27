package com.lazydevs.notification.api.push;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A registered push device (DD-37), as returned by a {@link PushDeviceRegistry}.
 *
 * <p>The token is personal data: {@link #toString()} shows its
 * {@link PushTokens#hash(String) hash} only, and the library never logs it.
 *
 * @param tenantId       tenant scope of the device, {@code null} for the pool (no tenant) scope
 * @param userId         the user the device belongs to
 * @param installationId the app installation, stable across token rotation
 * @param appId          the app, may be {@code null}
 * @param platform       the platform
 * @param token          the push token
 * @param tokenHash      {@link PushTokens#hash(String)} of the token
 * @param locale         BCP-47 locale, may be {@code null}
 * @param timezone       IANA time zone, may be {@code null}
 * @param attributes     free-form string attributes, never {@code null}
 * @param createdAt      when the installation was first registered
 * @param lastSeenAt     when the installation last registered (or refreshed its token)
 * @since 1.2.3
 */
public record PushDevice(
        String tenantId,
        String userId,
        String installationId,
        String appId,
        PushPlatform platform,
        String token,
        String tokenHash,
        String locale,
        String timezone,
        Map<String, String> attributes,
        Instant createdAt,
        Instant lastSeenAt) {

    public PushDevice {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(installationId, "installationId");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        tokenHash = tokenHash == null ? PushTokens.hash(token) : tokenHash;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * The device a registration describes, created and seen at {@code now}.
     *
     * @param registration the registration
     * @param tenantId     the resolved tenant scope, may be {@code null}
     * @param now          the registration time
     * @return the device
     */
    public static PushDevice from(PushDeviceRegistration registration, String tenantId, Instant now) {
        return new PushDevice(tenantId, registration.userId(), registration.installationId(),
                registration.appId(), registration.platform(), registration.token(), registration.tokenHash(),
                registration.locale(), registration.timezone(), registration.attributes(), now, now);
    }

    /**
     * Whether the device was seen within {@code staleAfter} before {@code now}, so a
     * notification to its user reaches it: the rule {@link PushDeviceRegistry#activeDevices}
     * applies. A device seen exactly {@code staleAfter} ago is still active.
     *
     * @param staleAfter {@code notification.push.devices.stale-after}, positive
     * @param now        the time to judge at
     * @return whether the device is active at {@code now}
     * @since 1.3.1
     */
    public boolean isActive(Duration staleAfter, Instant now) {
        Objects.requireNonNull(staleAfter, "staleAfter");
        Objects.requireNonNull(now, "now");
        return !lastSeenAt.isBefore(now.minus(staleAfter));
    }

    /** Shows the token hash, never the token. */
    @Override
    public String toString() {
        return "PushDevice[tenantId=" + tenantId + ", userId=" + userId + ", installationId=" + installationId
                + ", appId=" + appId + ", platform=" + platform + ", tokenHash=" + tokenHash
                + ", locale=" + locale + ", timezone=" + timezone + ", attributes=" + attributes
                + ", createdAt=" + createdAt + ", lastSeenAt=" + lastSeenAt + "]";
    }
}
