package com.lazydevs.notification.api.push;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushDeviceTest {

    private static final String TOKEN = "fcm-token-that-must-never-appear-in-logs";

    @Test
    void registrationStripsAndDropsBlankOptionals() {
        PushDeviceRegistration registration = new PushDeviceRegistration(" ", " u-1 ", " inst-1 ", "",
                PushPlatform.ANDROID, " " + TOKEN + " ", " en-GB ", " ", null);

        assertThat(registration.tenantId()).isNull();
        assertThat(registration.userId()).isEqualTo("u-1");
        assertThat(registration.installationId()).isEqualTo("inst-1");
        assertThat(registration.appId()).isNull();
        assertThat(registration.token()).isEqualTo(TOKEN);
        assertThat(registration.locale()).isEqualTo("en-GB");
        assertThat(registration.timezone()).isNull();
        assertThat(registration.attributes()).isEmpty();
        assertThat(registration.tokenHash()).isEqualTo(PushTokens.hash(TOKEN));
    }

    @Test
    void registrationRejectsMissingOrOversizedValues() {
        assertThatThrownBy(() -> PushDeviceRegistration.of(" ", "i", PushPlatform.IOS, TOKEN))
                .hasMessageContaining("userId");
        assertThatThrownBy(() -> PushDeviceRegistration.of("u", null, PushPlatform.IOS, TOKEN))
                .hasMessageContaining("installationId");
        assertThatThrownBy(() -> PushDeviceRegistration.of("u", "i", null, TOKEN))
                .hasMessageContaining("platform");
        assertThatThrownBy(() -> PushDeviceRegistration.of("u", "i", PushPlatform.WEB, ""))
                .hasMessageContaining("token");
        assertThatThrownBy(() -> PushDeviceRegistration.of("u".repeat(256), "i", PushPlatform.WEB, TOKEN))
                .hasMessageContaining("userId must be at most 255");
        Map<String, String> withNull = new HashMap<>();
        withNull.put("k", null);
        assertThatThrownBy(() -> new PushDeviceRegistration(null, "u", "i", null, PushPlatform.WEB, TOKEN,
                null, null, withNull)).hasMessageContaining("attributes");
    }

    @Test
    void registrationRejectsControlCharactersInUserAndInstallationIds() {
        for (String bad : new String[] {"a\u0000b", "a\nb", "a\tb", "a\u001fb", "a\u007fb"}) {
            assertThatThrownBy(() -> PushDeviceRegistration.of(bad, "i", PushPlatform.IOS, TOKEN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("userId must not contain control characters");
            assertThatThrownBy(() -> PushDeviceRegistration.of("u", bad, PushPlatform.IOS, TOKEN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("installationId must not contain control characters");
        }
        // Surrounding whitespace is stripped first, as before.
        PushDeviceRegistration stripped = PushDeviceRegistration.of(" u\n", "\ti ", PushPlatform.IOS, TOKEN);
        assertThat(stripped.userId()).isEqualTo("u");
        assertThat(stripped.installationId()).isEqualTo("i");
    }

    @Test
    void toStringNeverShowsTheToken() {
        PushDeviceRegistration registration = new PushDeviceRegistration("acme", "u-1", "inst-1", "app",
                PushPlatform.IOS, TOKEN, null, null, Map.of("appVersion", "1.2"));
        PushDevice device = PushDevice.from(registration, "acme", Instant.EPOCH);

        assertThat(registration.toString()).doesNotContain(TOKEN).contains(PushTokens.hash(TOKEN));
        assertThat(device.toString()).doesNotContain(TOKEN).contains(PushTokens.hash(TOKEN));
    }

    @Test
    void deviceFromRegistrationCarriesEveryValue() {
        PushDeviceRegistration registration = new PushDeviceRegistration(null, "u-1", "inst-1", "app",
                PushPlatform.WEB, TOKEN, "de-DE", "Europe/Berlin", Map.of("model", "x"));
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        PushDevice device = PushDevice.from(registration, "acme", now);

        assertThat(device).isEqualTo(new PushDevice("acme", "u-1", "inst-1", "app", PushPlatform.WEB, TOKEN,
                PushTokens.hash(TOKEN), "de-DE", "Europe/Berlin", Map.of("model", "x"), now, now));
        assertThat(new PushDevice(null, "u", "i", null, PushPlatform.WEB, TOKEN, null, null, null, null, now, now)
                .tokenHash()).isEqualTo(PushTokens.hash(TOKEN));
    }

    @Test
    void isActive_whileSeenWithinStaleAfter_inclusive() {
        Instant seen = Instant.parse("2026-01-01T00:00:00Z");
        Duration staleAfter = Duration.ofDays(60);
        PushDevice device = new PushDevice(null, "u", "i", null, PushPlatform.IOS, TOKEN, null, null, null, null,
                seen, seen);

        assertThat(device.isActive(staleAfter, seen)).isTrue();
        assertThat(device.isActive(staleAfter, seen.plus(staleAfter))).isTrue();
        assertThat(device.isActive(staleAfter, seen.plus(staleAfter).plusMillis(1))).isFalse();
        assertThatThrownBy(() -> device.isActive(null, seen)).hasMessageContaining("staleAfter");
        assertThatThrownBy(() -> device.isActive(staleAfter, null)).hasMessageContaining("now");
    }
}
