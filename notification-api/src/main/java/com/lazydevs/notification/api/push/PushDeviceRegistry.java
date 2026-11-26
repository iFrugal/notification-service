package com.lazydevs.notification.api.push;

import java.time.Instant;
import java.util.List;

/**
 * Stores the push devices of users (DD-37), so a notification can address a
 * user instead of device tokens and dead tokens are cleaned up.
 *
 * <p><b>Scope.</b> Every operation works in the current tenant scope: the
 * {@code TenantContext} tenant, or the pool scope (no tenant) when none is set.
 * {@link #register} uses {@link PushDeviceRegistration#tenantId()} instead when
 * the registration names one. A device in one scope is invisible in every other.
 *
 * <p><b>Semantics</b>, the same for every implementation:
 * <ul>
 *   <li>{@link #register} is an upsert on {@code (scope, userId, installationId)}, in
 *       one transaction: it first removes the same token from any other user or
 *       installation in the scope (reason {@link PushDeviceRemovalReasons#MOVED}, a shared
 *       phone), then inserts or refreshes the device ({@code lastSeenAt} is now; a new token
 *       for the same installation is a token rotation), then removes the least recently
 *       seen devices of the user beyond {@code max-devices-per-user}
 *       (reason {@link PushDeviceRemovalReasons#EVICTED}).</li>
 *   <li>{@link #activeDevices} leaves out devices not seen within {@code stale-after}.</li>
 *   <li>The unregister operations are idempotent: removing what is not there is not an error.</li>
 *   <li>Every removal is logged at INFO with the token hash and the reason; the token
 *       itself is never logged.</li>
 * </ul>
 *
 * <p>Implementations are thread-safe. The library ships an in-memory registry
 * (notification-core) and a JDBC registry (notification-store-jdbc); both are opt-in
 * with {@code notification.push.devices.enabled=true}.
 *
 * @since 1.4.0
 */
public interface PushDeviceRegistry {

    /**
     * Register or refresh a device.
     *
     * @param registration the device
     * @return the stored device, with its creation and last-seen times
     */
    PushDevice register(PushDeviceRegistration registration);

    /**
     * Remove one installation of a user. Idempotent.
     *
     * @param userId         the user
     * @param installationId the installation
     * @return whether a device was removed
     */
    boolean unregister(String userId, String installationId);

    /**
     * Remove the device holding {@code token}, whoever it belongs to. Idempotent.
     *
     * @param token the push token
     * @return whether a device was removed
     */
    boolean unregisterToken(String token);

    /**
     * Remove every device of a user, for example on sign-out everywhere or account deletion.
     * Idempotent.
     *
     * @param userId the user
     * @return the number of devices removed
     */
    int unregisterAll(String userId);

    /**
     * The devices a notification to the user goes to: those seen within {@code stale-after},
     * most recently seen first.
     *
     * @param userId the user
     * @param appId  only devices of this app; {@code null} for every app
     * @return the devices, possibly empty
     */
    List<PushDevice> activeDevices(String userId, String appId);

    /**
     * Remove every device whose token has this {@link PushTokens#hash(String) hash}, for
     * example after the push service reported the token as invalid.
     *
     * @param tokenHash the token hash, {@code sha256:<16 hex>}
     * @param reason    why, one of {@link PushDeviceRemovalReasons}; logged
     * @return the number of devices removed
     */
    int removeByTokenHash(String tokenHash, String reason);

    /**
     * Remove the devices not seen since {@code olderThan}
     * (reason {@link PushDeviceRemovalReasons#STALE}).
     *
     * @param olderThan the cut-off; devices last seen before it are removed
     * @return the number of devices removed
     */
    int purgeStale(Instant olderThan);
}
