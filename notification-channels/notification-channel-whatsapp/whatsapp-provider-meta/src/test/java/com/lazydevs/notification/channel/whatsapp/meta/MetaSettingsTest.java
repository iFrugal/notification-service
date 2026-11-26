package com.lazydevs.notification.channel.whatsapp.meta;

import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetaSettingsTest {

    @Test
    void defaults() {
        MetaSettings settings = MetaSettings.defaults();

        assertThat(settings.baseUrl()).isEqualTo(URI.create("https://graph.facebook.com"));
        assertThat(settings.apiVersion()).isEqualTo("v25.0");
        assertThat(settings.timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.pairRateLimitRetryAfter()).isEqualTo(Duration.ofSeconds(6));
        assertThat(settings.staticCredentials()).isEmpty();
        assertThat(settings.messagesUri("123"))
                .isEqualTo(URI.create("https://graph.facebook.com/v25.0/123/messages"));
    }

    @Test
    void parsesEveryKey_andHidesTheToken() {
        Map<String, Object> props = new HashMap<>();
        props.put("base-url", "http://127.0.0.1:8089/");
        props.put("api-version", "v24.0");
        props.put("timeout", "500ms");
        props.put("pair-rate-limit-retry-after", "PT10S");
        props.put("access-token", " your-access-token ");
        props.put("phone-number-id", "100000000000001");
        props.put("waba-id", "200000000000002");

        MetaSettings settings = MetaSettings.fromMap(props);

        assertThat(settings.baseUrl()).isEqualTo(URI.create("http://127.0.0.1:8089"));
        assertThat(settings.apiVersion()).isEqualTo("v24.0");
        assertThat(settings.timeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(settings.pairRateLimitRetryAfter()).isEqualTo(Duration.ofSeconds(10));
        MetaCredentials credentials = settings.staticCredentials().orElseThrow();
        assertThat(credentials.accessToken()).isEqualTo("your-access-token");
        assertThat(credentials.wabaId()).isEqualTo("200000000000002");
        assertThat(settings.toString()).doesNotContain("your-access-token");
        assertThat(credentials.toString()).doesNotContain("your-access-token");
    }

    @Test
    void acceptsDurationObjectsNumbersAndUnits() {
        assertThat(MetaSettings.fromMap(Map.of("timeout", Duration.ofSeconds(3))).timeout()).hasSeconds(3);
        assertThat(MetaSettings.fromMap(Map.of("timeout", 4)).timeout()).hasSeconds(4);
        assertThat(MetaSettings.fromMap(Map.of("timeout", "2m")).timeout()).hasMinutes(2);
        assertThat(MetaSettings.fromMap(Map.of("timeout", "1h")).timeout()).hasHours(1);
        assertThat(MetaSettings.fromMap(Map.of("timeout", " ")).timeout()).hasSeconds(10);
        assertThat(MetaSettings.fromMap(Map.of("base-url", "http://localhost:9")).baseUrl().getHost())
                .isEqualTo("localhost");
        assertThat(MetaSettings.fromMap(null).apiVersion()).isEqualTo("v25.0");
    }

    @Test
    void rejectsMalformedValues() {
        assertInvalid(Map.of("base-url", "http://graph.example.com"), "https");
        assertInvalid(Map.of("base-url", "https://graph.example.com?x=1"), "without query");
        assertInvalid(Map.of("base-url", "not a url"), "base-url");
        assertInvalid(Map.of("base-url", "relative/path"), "absolute");
        assertInvalid(Map.of("api-version", "25"), "v25.0");
        assertInvalid(Map.of("timeout", "0s"), "positive");
        assertInvalid(Map.of("timeout", "soon"), "not a duration");
        assertInvalid(Map.of("pair-rate-limit-retry-after", "-PT1S"), "negative");
        assertInvalid(Map.of("access-token", "t"), "set both");
        assertInvalid(Map.of("phone-number-id", "1"), "set both");
        assertInvalid(Map.of("access-token", "t", "phone-number-id", "../x"), "numeric");
        assertInvalid(Map.of("access-token", "t", "phone-number-id", "1", "waba-id", "abc"), "numeric");
    }

    @Test
    void credentialsValidate() {
        assertThatThrownBy(() -> new MetaCredentials(" ", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetaCredentials("t", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new MetaCredentials("t", " 12 ", " ").wabaId()).isNull();
        assertThat(new MetaCredentials("t", " 12 ").phoneNumberId()).isEqualTo("12");
    }

    private static void assertInvalid(Map<String, Object> props, String fragment) {
        assertThatThrownBy(() -> MetaSettings.fromMap(props))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining(fragment);
    }
}
