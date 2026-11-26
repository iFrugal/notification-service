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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

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

    @Test
    void idPairsThatConcatenateAlike_areDistinctDevices() {
        registry.register(PushDeviceRegistration.of("ab", "c", PushPlatform.ANDROID, "t1"));
        registry.register(PushDeviceRegistration.of("a", "bc", PushPlatform.ANDROID, "t2"));

        assertThat(registry.activeDevices("ab", null)).extracting("token").containsExactly("t1");
        assertThat(registry.activeDevices("a", null)).extracting("token").containsExactly("t2");
        assertThat(registry.unregister("a", "bc")).isTrue();
        assertThat(registry.activeDevices("ab", null)).extracting("token").containsExactly("t1");
    }

    @Test
    void concurrentRegistrations_neverMoveLastSeenBackwards() throws Exception {
        // Every read of this clock is one millisecond later than the one before.
        AtomicLong ticks = new AtomicLong();
        Instant start = Instant.parse("2026-11-27T10:00:00Z");
        Clock ticking = new Clock() {
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
                return start.plusMillis(ticks.incrementAndGet());
            }
        };
        InMemoryPushDeviceRegistry shared = new InMemoryPushDeviceRegistry(MAX_DEVICES, STALE_AFTER, ticking);
        int threads = 8;
        int perThread = 500;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                done.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        shared.register(PushDeviceRegistration.of("u1", "inst", PushPlatform.ANDROID, "t1"));
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : done) {
                f.get();
            }
        }
        // The clock is read once per registration, under the lock: the last write holds the last reading.
        Instant lastIssued = start.plusMillis(ticks.get());
        assertThat(shared.activeDevices("u1", null)).singleElement()
                .satisfies(d -> assertThat(d.lastSeenAt()).isEqualTo(lastIssued));
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
