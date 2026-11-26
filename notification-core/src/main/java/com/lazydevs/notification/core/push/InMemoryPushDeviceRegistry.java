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
 * @since 1.4.0
 */
@Slf4j
public class InMemoryPushDeviceRegistry implements PushDeviceRegistry {

    /** Scope key of the pool (no tenant) scope, as the JDBC {@code tenant_key} column maps it. */
    private static final String POOL_SCOPE = "";

    private final int maxDevicesPerUser;
    private final Duration staleAfter;
    private final Clock clock;
    private final Object lock = new Object();

    /** Scope key, then {@code userId + '\0' + installationId}, then the device. */
    private final Map<String, Map<String, PushDevice>> scopes = new HashMap<>();

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
        String key = key(registration.userId(), registration.installationId());
        Instant now = clock.instant();
        synchronized (lock) {
            Map<String, PushDevice> devices = scopes.computeIfAbsent(scopeKey(tenant), k -> new HashMap<>());
            removeWhere(devices, d -> d.token().equals(registration.token()) && !key.equals(key(d)),
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
        String key = key(userId.strip(), installationId.strip());
        return removeInScope(d -> key.equals(key(d)), PushDeviceRemovalReasons.UNREGISTERED) > 0;
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
            return scopes.getOrDefault(scopeKey(currentTenant()), Map.of()).values().stream()
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

    private void evictBeyondLimit(Map<String, PushDevice> devices, PushDevice kept) {
        List<PushDevice> others = new ArrayList<>(devices.values().stream()
                .filter(d -> d.userId().equals(kept.userId()) && d != kept)
                .sorted(MOST_RECENT_FIRST)
                .toList());
        // The device just registered always stays.
        for (int i = maxDevicesPerUser - 1; i < others.size(); i++) {
            PushDevice evicted = others.get(i);
            devices.remove(key(evicted));
            logRemoval(PushDeviceRemovalReasons.EVICTED);
        }
    }

    private int removeInScope(Predicate<PushDevice> match, String reason) {
        synchronized (lock) {
            Map<String, PushDevice> devices = scopes.get(scopeKey(currentTenant()));
            return devices == null ? 0 : removeWhere(devices, match, reason);
        }
    }

    private static int removeWhere(Map<String, PushDevice> devices, Predicate<PushDevice> match, String reason) {
        List<PushDevice> removed = devices.values().stream().filter(match).toList();
        removed.forEach(d -> {
            devices.remove(key(d));
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

    private static String scopeKey(String tenant) {
        return tenant == null ? POOL_SCOPE : tenant;
    }

    private static String key(PushDevice device) {
        return key(device.userId(), device.installationId());
    }

    private static String key(String userId, String installationId) {
        return userId + '\0' + installationId;
    }
}
