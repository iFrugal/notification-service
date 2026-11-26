package com.lazydevs.notification.core.push;

import com.lazydevs.notification.api.push.PushDeviceRegistration;
import com.lazydevs.notification.api.push.PushDeviceRegistry;
import com.lazydevs.notification.api.push.PushPlatform;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry contract over {@link InMemoryPushDeviceRegistry}, plus its own argument checks.
 * Ageing the devices moves the registry's clock forward instead.
 */
class InMemoryPushDeviceRegistryTest extends PushDeviceRegistryContractTest {

    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-27T10:00:00Z"));

    @Override
    protected PushDeviceRegistry newRegistry() {
        return new InMemoryPushDeviceRegistry(MAX_DEVICES, STALE_AFTER, clock);
    }

    @Override
    protected void ageDevices(Duration by) {
        clock.advance(by);
    }

    @Override
    protected Instant now() {
        return clock.instant();
    }

    @Test
    void rejectsAMaximumBelowOneAndANonPositiveStaleAge() {
        assertThatThrownBy(() -> new InMemoryPushDeviceRegistry(0, STALE_AFTER, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxDevicesPerUser");
        assertThatThrownBy(() -> new InMemoryPushDeviceRegistry(1, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("staleAfter");
    }

    @Test
    void nullArgumentsAreNoOps() {
        assertThat(registry.unregister(null, "inst")).isFalse();
        assertThat(registry.unregisterToken(" ")).isFalse();
        assertThat(registry.unregisterAll(null)).isZero();
        assertThat(registry.activeDevices(null, null)).isEmpty();
        assertThat(registry.removeByTokenHash(null, "x")).isZero();
    }

    @Test
    void maxOneDevice_keepsOnlyTheNewest() {
        PushDeviceRegistry single = new InMemoryPushDeviceRegistry(1, STALE_AFTER, clock);
        single.register(PushDeviceRegistration.of("u1", "inst-1", PushPlatform.ANDROID, "t1"));
        clock.advance(Duration.ofSeconds(1));
        single.register(PushDeviceRegistration.of("u1", "inst-2", PushPlatform.ANDROID, "t2"));

        assertThat(single.activeDevices("u1", null)).extracting("token").containsExactly("t2");
    }

    /** A clock the test moves forward. */
    static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
