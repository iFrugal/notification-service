package com.lazydevs.notification.api.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DD-38: template keys are normalised, validated as safe path segments and map to template
 * names under the base path both ways.
 */
class TemplateKeyTest {

    @Test
    void defaultTenantNamesTheDefaultLayer() {
        assertThat(TemplateKey.of("default", "EMAIL", "WELCOME")).isEqualTo(TemplateKey.of(null, "email", "WELCOME"));
        assertThat(TemplateKey.of(" ", "email", "WELCOME").isDefaultLayer()).isTrue();
        assertThat(TemplateKey.of(null, "email", "WELCOME").name()).isEqualTo("default/email/WELCOME.ftl");
        assertThat(TemplateKey.of("acme", "email", "WELCOME").name()).isEqualTo("acme/email/WELCOME.ftl");
        assertThat(TemplateKey.of("acme", "email", "WELCOME").defaultLayer())
                .isEqualTo(TemplateKey.of(null, "email", "WELCOME"));
        assertThat(new TemplateKey("acme", "sms", "OTP", null).locale()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "..", "a/b", "a\\b", "_partial", "a..b", "x y"})
    void unsafeSegmentsAreRejected(String bad) {
        assertThat(TemplateKey.isValidSegment(bad)).isFalse();
        assertThatThrownBy(() -> TemplateKey.of("acme", "email", bad)).isInstanceOf(IllegalArgumentException.class);
        String channel = bad.isEmpty() ? null : bad;
        assertThatThrownBy(() -> TemplateKey.of("acme", channel, "T"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void longSegmentsAreRejected() {
        assertThat(TemplateKey.isValidSegment("a".repeat(128))).isTrue();
        assertThat(TemplateKey.isValidSegment("a".repeat(129))).isFalse();
    }

    @Test
    void namesMapBackToKeysOnlyWhenShapedLikeOne() {
        assertThat(TemplateKey.fromName("default/email/WELCOME.ftl"))
                .contains(TemplateKey.of(null, "email", "WELCOME"));
        assertThat(TemplateKey.fromName("acme/sms/OTP.ftl")).contains(TemplateKey.of("acme", "sms", "OTP"));
        assertThat(TemplateKey.fromName("shared/lib.ftl")).isEmpty();
        assertThat(TemplateKey.fromName("default/email/parts/x.ftl")).isEmpty();
        assertThat(TemplateKey.fromName("default/email/_layout.ftl")).isEmpty();
        assertThat(TemplateKey.fromName("default/EMAIL/WELCOME.ftl")).isEmpty();
        assertThat(TemplateKey.fromName("default/email/WELCOME.txt")).isEmpty();
        assertThat(TemplateKey.fromName(null)).isEmpty();
    }

    @Test
    void filterMatchesChannelCaseInsensitively() {
        TemplateKey key = TemplateKey.of("acme", "email", "WELCOME");
        assertThat(new TemplateFilter("EMAIL", null).matches(key)).isTrue();
        assertThat(new TemplateFilter(null, "WELCOME").matches(key)).isTrue();
        assertThat(new TemplateFilter("sms", null).matches(key)).isFalse();
        assertThat(TemplateFilter.all().matches(key)).isTrue();
    }
}
