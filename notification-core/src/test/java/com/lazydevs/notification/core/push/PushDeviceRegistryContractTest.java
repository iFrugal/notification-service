package com.lazydevs.notification.core.push;

import com.lazydevs.notification.api.push.PushDevice;
import com.lazydevs.notification.api.push.PushDeviceRegistration;
import com.lazydevs.notification.api.push.PushDeviceRegistry;
import com.lazydevs.notification.api.push.PushDeviceRemovalReasons;
import com.lazydevs.notification.api.push.PushPlatform;
import com.lazydevs.notification.api.push.PushTokens;
import lazydevs.persistence.connection.multitenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link PushDeviceRegistry} semantics every implementation shares (DD-37).
 *
 * <p>Shipped in the notification-core test-jar: the in-memory registry runs it here and
 * the JDBC registry runs it in notification-store-jdbc. A subclass creates a registry with
 * {@link #MAX_DEVICES} and {@link #STALE_AFTER}, and makes stored devices older on demand.
 */
public abstract class PushDeviceRegistryContractTest {

    /** {@code max-devices-per-user} of the registry under test. */
    protected static final int MAX_DEVICES = 3;

    /** {@code stale-after} of the registry under test. */
    protected static final Duration STALE_AFTER = Duration.ofDays(60);

    private static final Duration STEP = Duration.ofMinutes(1);

    protected PushDeviceRegistry registry;

    /**
     * @return an empty registry with {@link #MAX_DEVICES} and {@link #STALE_AFTER}
     */
    protected abstract PushDeviceRegistry newRegistry();

    /**
     * Make every stored device, in every scope, look {@code by} older: its creation and
     * last-seen times move back, and later registrations happen after them.
     */
    protected abstract void ageDevices(Duration by);

    /** @return the registry's current time, which decides last-seen times and staleness */
    protected abstract Instant now();

    @BeforeEach
    void createRegistry() {
        TenantContext.reset();
        registry = newRegistry();
    }

    @AfterEach
    void resetTenant() {
        TenantContext.reset();
    }

    private PushDevice register(String user, String installation, String token) {
        PushDevice device = registry.register(PushDeviceRegistration.of(user, installation, PushPlatform.ANDROID, token));
        // Every registration is seen later than the one before it.
        ageDevices(STEP);
        return device;
    }

    private List<String> tokensOf(String user) {
        return registry.activeDevices(user, null).stream().map(PushDevice::token).toList();
    }

    @Test
    void register_storesTheDevice_andReturnsItWithItsTimes() {
        PushDevice device = registry.register(new PushDeviceRegistration(null, "u1", "inst-1", "app",
                PushPlatform.IOS, "token-1", "en-IN", "Asia/Kolkata", Map.of("model", "iPhone")));

        assertThat(device.userId()).isEqualTo("u1");
        assertThat(device.installationId()).isEqualTo("inst-1");
        assertThat(device.tokenHash()).isEqualTo(PushTokens.hash("token-1"));
        assertThat(device.createdAt()).isEqualTo(device.lastSeenAt());
        assertThat(device.tenantId()).isNull();
        assertThat(registry.activeDevices("u1", null)).singleElement().satisfies(stored -> {
            assertThat(stored.token()).isEqualTo("token-1");
            assertThat(stored.platform()).isEqualTo(PushPlatform.IOS);
            assertThat(stored.appId()).isEqualTo("app");
            assertThat(stored.locale()).isEqualTo("en-IN");
            assertThat(stored.timezone()).isEqualTo("Asia/Kolkata");
            assertThat(stored.attributes()).containsExactly(Map.entry("model", "iPhone"));
        });
    }

    @Test
    void registerAgain_refreshesLastSeen_andKeepsCreatedAt() {
        PushDevice first = register("u1", "inst-1", "token-1");

        PushDevice again = registry.register(PushDeviceRegistration.of("u1", "inst-1", PushPlatform.ANDROID, "token-1"));

        assertThat(again.lastSeenAt()).isAfter(again.createdAt());
        assertThat(again.createdAt()).isBeforeOrEqualTo(first.createdAt());
        assertThat(registry.activeDevices("u1", null)).hasSize(1);
    }

    @Test
    void newTokenForTheSameInstallation_isATokenRotation() {
        register("u1", "inst-1", "old-token");

        register("u1", "inst-1", "new-token");

        assertThat(tokensOf("u1")).containsExactly("new-token");
        assertThat(registry.unregisterToken("old-token")).isFalse();
    }

    @Test
    void sameTokenForAnotherUser_movesTheDevice() {
        register("alice", "inst-a", "shared-phone");

        register("bob", "inst-b", "shared-phone");

        assertThat(tokensOf("alice")).isEmpty();
        assertThat(tokensOf("bob")).containsExactly("shared-phone");
    }

    @Test
    void sameTokenForAnotherInstallationOfTheUser_replacesTheOldInstallation() {
        register("u1", "reinstalled-1", "token-1");

        register("u1", "reinstalled-2", "token-1");

        assertThat(registry.activeDevices("u1", null)).extracting(PushDevice::installationId)
                .containsExactly("reinstalled-2");
    }

    @Test
    void beyondMaxDevices_theLeastRecentlySeenAreEvicted_andTheNewOneStays() {
        register("u1", "inst-1", "t1");
        register("u1", "inst-2", "t2");
        register("u1", "inst-3", "t3");
        register("u1", "inst-1", "t1"); // inst-2 is now the least recently seen

        register("u1", "inst-4", "t4");

        assertThat(tokensOf("u1")).containsExactly("t4", "t1", "t3");
        assertThat(registry.unregisterToken("t2")).isFalse();
    }

    @Test
    void eviction_countsOnlyTheUsersOwnDevices() {
        for (int i = 0; i < MAX_DEVICES; i++) {
            register("u1", "u1-inst-" + i, "u1-t" + i);
            register("u2", "u2-inst-" + i, "u2-t" + i);
        }

        assertThat(tokensOf("u1")).hasSize(MAX_DEVICES);
        assertThat(tokensOf("u2")).hasSize(MAX_DEVICES);
    }

    @Test
    void activeDevices_areMostRecentlySeenFirst_andFilteredByApp() {
        registry.register(new PushDeviceRegistration(null, "u1", "inst-1", "recruiter", PushPlatform.ANDROID,
                "t1", null, null, null));
        ageDevices(STEP);
        registry.register(new PushDeviceRegistration(null, "u1", "inst-2", "candidate", PushPlatform.IOS,
                "t2", null, null, null));
        ageDevices(STEP);

        assertThat(tokensOf("u1")).containsExactly("t2", "t1");
        assertThat(registry.activeDevices("u1", "recruiter")).extracting(PushDevice::token).containsExactly("t1");
        assertThat(registry.activeDevices("u1", "other")).isEmpty();
        assertThat(registry.activeDevices("nobody", null)).isEmpty();
    }

    @Test
    void aDeviceNotSeenWithinStaleAfter_isNotActive_untilItRegistersAgain() {
        register("u1", "inst-1", "t1");
        register("u1", "inst-2", "t2");
        ageDevices(STALE_AFTER.minus(STEP.multipliedBy(3)));
        register("u1", "inst-2", "t2");

        ageDevices(STEP.multipliedBy(3));

        assertThat(tokensOf("u1")).containsExactly("t2");
        register("u1", "inst-1", "t1");
        assertThat(tokensOf("u1")).containsExactly("t1", "t2");
    }

    @Test
    void devices_listEveryDeviceOfTheUser_staleOnesIncluded_mostRecentlySeenFirst() {
        registry.register(new PushDeviceRegistration(null, "u1", "inst-1", "recruiter", PushPlatform.ANDROID,
                "t1", null, null, null));
        ageDevices(STEP);
        register("u1", "inst-2", "t2");
        register("u2", "inst-3", "t3");
        ageDevices(STALE_AFTER);
        registry.register(new PushDeviceRegistration(null, "u1", "inst-4", "candidate", PushPlatform.IOS,
                "t4", null, null, null));
        ageDevices(STEP);

        List<PushDevice> devices = registry.devices("u1");

        assertThat(devices).extracting(PushDevice::token).containsExactly("t4", "t2", "t1");
        assertThat(devices).extracting(PushDevice::appId).containsExactly("candidate", null, "recruiter");
        assertThat(tokensOf("u1")).containsExactly("t4");
        // The helper judges staleness exactly as activeDevices does.
        Instant now = now();
        assertThat(devices).filteredOn(d -> d.isActive(STALE_AFTER, now))
                .isEqualTo(registry.activeDevices("u1", null));
        assertThat(devices).extracting(d -> d.isActive(STALE_AFTER, now)).containsExactly(true, false, false);
    }

    @Test
    void devices_ofAnUnknownUser_areEmpty() {
        register("u1", "inst-1", "t1");

        assertThat(registry.devices("nobody")).isEmpty();
        assertThat(registry.devices(null)).isEmpty();
        assertThat(registry.devices(" u1 ")).extracting(PushDevice::token).containsExactly("t1");
    }

    @Test
    void devices_areThoseOfTheCurrentScopeOnly() {
        TenantContext.setTenantId("acme");
        register("u1", "inst-1", "acme-token");
        TenantContext.setTenantId("globex");
        register("u1", "inst-2", "globex-token");
        TenantContext.reset();
        register("u1", "inst-3", "pool-token");
        registry.register(new PushDeviceRegistration("acme", "u1", "inst-4", null, PushPlatform.WEB,
                "acme-token-2", null, null, null));
        ageDevices(STEP);

        assertThat(registry.devices("u1")).extracting(PushDevice::token).containsExactly("pool-token");
        TenantContext.setTenantId("acme");
        assertThat(registry.devices("u1")).extracting(PushDevice::token)
                .containsExactly("acme-token-2", "acme-token");
        assertThat(registry.devices("u1")).extracting(PushDevice::tenantId).containsOnly("acme");
        TenantContext.setTenantId("globex");
        assertThat(registry.devices("u1")).extracting(PushDevice::token).containsExactly("globex-token");
        TenantContext.setTenantId("initech");
        assertThat(registry.devices("u1")).isEmpty();
    }

    @Test
    void unregister_removesOneInstallation_andIsIdempotent() {
        register("u1", "inst-1", "t1");
        register("u1", "inst-2", "t2");

        assertThat(registry.unregister("u1", "inst-1")).isTrue();
        assertThat(registry.unregister("u1", "inst-1")).isFalse();
        assertThat(registry.unregister("u2", "inst-2")).isFalse();
        assertThat(tokensOf("u1")).containsExactly("t2");
    }

    @Test
    void unregisterToken_removesTheDeviceWhoeverOwnsIt_andIsIdempotent() {
        register("u1", "inst-1", "t1");

        assertThat(registry.unregisterToken(" t1 ")).isTrue();
        assertThat(registry.unregisterToken("t1")).isFalse();
        assertThat(tokensOf("u1")).isEmpty();
    }

    @Test
    void unregisterAll_removesEveryDeviceOfTheUser() {
        register("u1", "inst-1", "t1");
        register("u1", "inst-2", "t2");
        register("u2", "inst-3", "t3");

        assertThat(registry.unregisterAll("u1")).isEqualTo(2);
        assertThat(registry.unregisterAll("u1")).isZero();
        assertThat(tokensOf("u2")).containsExactly("t3");
    }

    @Test
    void removeByTokenHash_removesTheDeviceWithThatHash() {
        register("u1", "inst-1", "t1");
        register("u1", "inst-2", "t2");

        assertThat(registry.removeByTokenHash(PushTokens.hash("t1"), PushDeviceRemovalReasons.INVALID_TARGET))
                .isEqualTo(1);
        assertThat(registry.removeByTokenHash(PushTokens.hash("t1"), PushDeviceRemovalReasons.INVALID_TARGET))
                .isZero();
        assertThat(registry.removeByTokenHash("sha256:0000000000000000", "reason\nforged")).isZero();
        assertThat(tokensOf("u1")).containsExactly("t2");
    }

    @Test
    void purgeStale_removesDevicesLastSeenBeforeTheCutOff() {
        register("u1", "inst-1", "t1");
        ageDevices(Duration.ofDays(300));
        register("u1", "inst-2", "t2");

        int purged = registry.purgeStale(now().minus(Duration.ofDays(270)));

        assertThat(purged).isEqualTo(1);
        assertThat(registry.unregisterToken("t1")).isFalse();
        assertThat(registry.unregisterToken("t2")).isTrue();
    }

    @Test
    void tenants_areSeparateScopes() {
        TenantContext.setTenantId("acme");
        register("u1", "inst-1", "same-token");
        TenantContext.setTenantId("globex");
        register("u1", "inst-1", "same-token");
        TenantContext.reset();
        register("u1", "inst-1", "pool-token");

        TenantContext.setTenantId("acme");
        assertThat(registry.activeDevices("u1", null)).singleElement()
                .satisfies(d -> assertThat(d.tenantId()).isEqualTo("acme"));
        assertThat(registry.unregisterAll("u1")).isEqualTo(1);
        TenantContext.setTenantId("globex");
        assertThat(tokensOf("u1")).containsExactly("same-token");
        TenantContext.reset();
        assertThat(tokensOf("u1")).containsExactly("pool-token");
    }

    @Test
    void aRegistrationNamingATenant_isStoredInThatScope() {
        registry.register(new PushDeviceRegistration("acme", "u1", "inst-1", null, PushPlatform.WEB, "t1",
                null, null, null));

        assertThat(tokensOf("u1")).isEmpty();
        TenantContext.setTenantId("acme");
        assertThat(tokensOf("u1")).containsExactly("t1");
    }
}
