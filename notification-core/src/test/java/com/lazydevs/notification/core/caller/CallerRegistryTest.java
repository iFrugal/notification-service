package com.lazydevs.notification.core.caller;

import com.lazydevs.notification.core.caller.CallerRegistry.Decision;
import com.lazydevs.notification.core.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the DD-11 caller-registry admission matrix.
 *
 * <p>Covers every cell of the table from DD-11 §"Caller registry":
 * <pre>
 *   enabled=false              → ACCEPT (everything)
 *   enabled=true,strict=false  → unknown → ACCEPT_WITH_WARNING; known → ACCEPT
 *   enabled=true,strict=true   → unknown → REJECT; known → ACCEPT
 *   callerId == null/blank     → ACCEPT in every mode
 * </pre>
 */
class CallerRegistryTest {

    private NotificationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
    }

    @Test
    void disabled_accepts_anyCaller() {
        properties.getCallerRegistry().setEnabled(false);
        properties.getCallerRegistry().setStrict(true); // strict ignored when disabled
        properties.getCallerRegistry().setKnownServices(List.of("billing-svc"));
        CallerRegistry registry = new CallerRegistry(properties);

        assertThat(registry.admit("billing-svc")).isEqualTo(Decision.ACCEPT);
        assertThat(registry.admit("unknown-svc")).isEqualTo(Decision.ACCEPT);
        assertThat(registry.admit(null)).isEqualTo(Decision.ACCEPT);
    }

    @Test
    void enabled_nonStrict_unknown_acceptsWithWarning() {
        properties.getCallerRegistry().setEnabled(true);
        properties.getCallerRegistry().setStrict(false);
        properties.getCallerRegistry().setKnownServices(List.of("billing-svc"));
        CallerRegistry registry = new CallerRegistry(properties);

        assertThat(registry.admit("unknown-svc")).isEqualTo(Decision.ACCEPT_WITH_WARNING);
        assertThat(registry.admit("billing-svc")).isEqualTo(Decision.ACCEPT);
    }

    @Test
    void enabled_strict_unknown_rejects() {
        properties.getCallerRegistry().setEnabled(true);
        properties.getCallerRegistry().setStrict(true);
        properties.getCallerRegistry().setKnownServices(List.of("billing-svc", "marketing-svc"));
        CallerRegistry registry = new CallerRegistry(properties);

        assertThat(registry.admit("unknown-svc")).isEqualTo(Decision.REJECT);
        assertThat(registry.admit("billing-svc")).isEqualTo(Decision.ACCEPT);
        assertThat(registry.admit("marketing-svc")).isEqualTo(Decision.ACCEPT);
    }

    @Test
    void nullOrBlank_callerId_alwaysAccepted_evenInStrict() {
        // DD-11: missing X-Service-Id is *not* a rejection condition.
        // Strict mode rejects unknown identified callers, not anonymous ones.
        properties.getCallerRegistry().setEnabled(true);
        properties.getCallerRegistry().setStrict(true);
        properties.getCallerRegistry().setKnownServices(List.of("billing-svc"));
        CallerRegistry registry = new CallerRegistry(properties);

        assertThat(registry.admit(null)).isEqualTo(Decision.ACCEPT);
        assertThat(registry.admit("")).isEqualTo(Decision.ACCEPT);
        assertThat(registry.admit("   ")).isEqualTo(Decision.ACCEPT);
    }

    @Test
    void getKnownServices_returnsImmutableSnapshot_inConfigOrder() {
        properties.getCallerRegistry().setEnabled(true);
        // Using ArrayList so we can mutate it below — List.of() is immutable.
        properties.getCallerRegistry().setKnownServices(new ArrayList<>(List.of("a", "b", "c")));
        CallerRegistry registry = new CallerRegistry(properties);

        // Order preserved (LinkedHashSet under the hood) — the admin
        // endpoint relies on this for predictable JSON output.
        assertThat(registry.getKnownServices()).containsExactly("a", "b", "c");

        // Mutating the underlying list after construction must not affect
        // the registry's view — registry should have snapshotted on init.
        properties.getCallerRegistry().getKnownServices().add("d");
        assertThat(registry.getKnownServices()).containsExactly("a", "b", "c");
    }

    @Test
    void isKnown_independentOfEnabled() {
        properties.getCallerRegistry().setEnabled(false);
        properties.getCallerRegistry().setKnownServices(List.of("billing-svc"));
        CallerRegistry registry = new CallerRegistry(properties);

        // isKnown is the raw membership check — useful for tests.
        assertThat(registry.isKnown("billing-svc")).isTrue();
        assertThat(registry.isKnown("unknown")).isFalse();
        assertThat(registry.isKnown(null)).isFalse();
    }

    @Test
    void emptyKnownServicesList_strict_rejectsEveryIdentifiedCaller() {
        // Edge case: registry on, strict on, but list is empty. Every
        // identified caller is unknown and rejected. Anonymous still passes.
        properties.getCallerRegistry().setEnabled(true);
        properties.getCallerRegistry().setStrict(true);
        properties.getCallerRegistry().setKnownServices(List.of());
        CallerRegistry registry = new CallerRegistry(properties);

        assertThat(registry.admit("any-svc")).isEqualTo(Decision.REJECT);
        assertThat(registry.admit(null)).isEqualTo(Decision.ACCEPT);
    }
}
