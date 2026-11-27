package com.lazydevs.notification.core.push;

import com.lazydevs.notification.api.push.PushDevice;
import com.lazydevs.notification.api.push.PushDeviceRegistration;
import com.lazydevs.notification.api.push.PushDeviceRegistry;
import com.lazydevs.notification.api.push.PushDeviceRemovalReasons;
import lazydevs.persistence.connection.multitenant.TenantContext;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * In-process {@link PushDeviceRegistry} (DD-37), the default when
 * {@code notification.push.devices.enabled=true} and no other registry exists.
 *
 * <p>Devices live in this JVM only and are lost on restart, so it suits tests,
 * development and single-instance deployments; use the JDBC registry of
 * notification-store-jdbc for anything else. Thread-safe: every operation holds
 * one lock, which is ample for registration traffic. The clock decides
 * {@code createdAt}, {@code lastSeenAt} and staleness.
 *
 * @since 1.2.3
 */
@Slf4j
public class InMemoryPushDeviceRegistry implements PushDeviceRegistry {

    private final int maxDevicesPerUser;
    private final Duration staleAfter;
    private final Clock clock;
    private final Object lock = new Object();

    /** The tenant ({@code null} for the pool scope), then the user and installation, then the device. */
    private final Map<String, Map<DeviceKey, PushDevice>> scopes = new HashMap<>();

    /** One device within a scope; a record, so no user or installation id can collide with another pair. */
    private record DeviceKey(String userId, String installationId) {

        static DeviceKey of(PushDevice device) {
            return new DeviceKey(device.userId(), device.installationId());
        }
    }

    /**
     * @param maxDevicesPerUser most devices one user keeps, at least 1
     * @param staleAfter        a device not seen for this long is not active
     * @param clock             the clock for timestamps and staleness
     */
    public InMemoryPushDeviceRegistry(int maxDevicesPerUser, Duration staleAfter, Clock clock) {
        if (maxDevicesPerUser < 1) {
            throw new IllegalArgumentException("maxDevicesPerUser must be at least 1, got " + maxDevicesPerUser);
        }
        if (staleAfter == null || !staleAfter.isPositive()) {
            throw new IllegalArgumentException("staleAfter must be positive, got " + staleAfter);
        }
        this.maxDevicesPerUser = maxDevicesPerUser;
        this.staleAfter = staleAfter;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PushDevice register(PushDeviceRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        String tenant = registration.tenantId() != null ? registration.tenantId() : currentTenant();
        DeviceKey key = new DeviceKey(registration.userId(), registration.installationId());
        synchronized (lock) {
            // Read under the lock, so a later registration never gets an earlier timestamp.
            Instant now = clock.instant();
            Map<DeviceKey, PushDevice> devices = scopes.computeIfAbsent(tenant, k -> new HashMap<>());
            removeWhere(devices, d -> d.token().equals(registration.token()) && !key.equals(DeviceKey.of(d)),
                    PushDeviceRemovalReasons.MOVED);
            PushDevice existing = devices.get(key);
            PushDevice fresh = PushDevice.from(registration, tenant, now);
            PushDevice stored = existing == null ? fresh : new PushDevice(fresh.tenantId(), fresh.userId(),
                    fresh.installationId(), fresh.appId(), fresh.platform(), fresh.token(), fresh.tokenHash(),
                    fresh.locale(), fresh.timezone(), fresh.attributes(), existing.createdAt(), now);
            devices.put(key, stored);
            evictBeyondLimit(devices, stored);
            return stored;
        }
    }

    @Override
    public boolean unregister(String userId, String installationId) {
        if (userId == null || installationId == null) {
            return false;
        }
        DeviceKey key = new DeviceKey(userId.strip(), installationId.strip());
        return removeInScope(d -> key.equals(DeviceKey.of(d)), PushDeviceRemovalReasons.UNREGISTERED) > 0;
    }

    @Override
    public boolean unregisterToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String stripped = token.strip();
        return removeInScope(d -> d.token().equals(stripped), PushDeviceRemovalReasons.UNREGISTERED) > 0;
    }

    @Override
    public int unregisterAll(String userId) {
        if (userId == null) {
            return 0;
        }
        String user = userId.strip();
        return removeInScope(d -> d.userId().equals(user), PushDeviceRemovalReasons.UNREGISTERED);
    }

    @Override
    public List<PushDevice> activeDevices(String userId, String appId) {
        if (userId == null) {
            return List.of();
        }
        String user = userId.strip();
        Instant activeSince = clock.instant().minus(staleAfter);
        synchronized (lock) {
            return scopes.getOrDefault(currentTenant(), Map.of()).values().stream()
                    .filter(d -> d.userId().equals(user))
                    .filter(d -> appId == null || appId.strip().equals(d.appId()))
                    .filter(d -> !d.lastSeenAt().isBefore(activeSince))
                    .sorted(MOST_RECENT_FIRST)
                    .toList();
        }
    }

    @Override
    public int removeByTokenHash(String tokenHash, String reason) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return 0;
        }
        String hash = tokenHash.strip();
        return removeInScope(d -> d.tokenHash().equals(hash), reason);
    }

    @Override
    public int purgeStale(Instant olderThan) {
        Objects.requireNonNull(olderThan, "olderThan");
        return removeInScope(d -> d.lastSeenAt().isBefore(olderThan), PushDeviceRemovalReasons.STALE);
    }

    /** Most recently seen first; the newest registration wins a tie. */
    private static final Comparator<PushDevice> MOST_RECENT_FIRST = Comparator
            .comparing(PushDevice::lastSeenAt, Comparator.reverseOrder())
            .thenComparing(PushDevice::createdAt, Comparator.reverseOrder());

    private void evictBeyondLimit(Map<DeviceKey, PushDevice> devices, PushDevice kept) {
        List<PushDevice> others = new ArrayList<>(devices.values().stream()
                .filter(d -> d.userId().equals(kept.userId()) && d != kept)
                .sorted(MOST_RECENT_FIRST)
                .toList());
        // The device just registered always stays.
        for (int i = maxDevicesPerUser - 1; i < others.size(); i++) {
            PushDevice evicted = others.get(i);
            devices.remove(DeviceKey.of(evicted));
            logRemoval(PushDeviceRemovalReasons.EVICTED);
        }
    }

    private int removeInScope(Predicate<PushDevice> match, String reason) {
        synchronized (lock) {
            Map<DeviceKey, PushDevice> devices = scopes.get(currentTenant());
            return devices == null ? 0 : removeWhere(devices, match, reason);
        }
    }

    private static int removeWhere(Map<DeviceKey, PushDevice> devices, Predicate<PushDevice> match, String reason) {
        List<PushDevice> removed = devices.values().stream().filter(match).toList();
        removed.forEach(d -> {
            devices.remove(DeviceKey.of(d));
            logRemoval(reason);
        });
        return removed.size();
    }

    /** One INFO line per removal: the reason only, never the token or anything derived from it. */
    private static void logRemoval(String reason) {
        log.info("Removed a push device ({})", sanitize(reason));
    }

    /** The reason may come from a caller; control characters would forge log lines. */
    private static String sanitize(String reason) {
        return reason == null ? "unspecified" : reason.replaceAll("\\p{Cntrl}", "_");
    }

    private static String currentTenant() {
        String tenant = TenantContext.getTenantId();
        return tenant == null || tenant.isBlank() ? null : tenant;
    }
}
