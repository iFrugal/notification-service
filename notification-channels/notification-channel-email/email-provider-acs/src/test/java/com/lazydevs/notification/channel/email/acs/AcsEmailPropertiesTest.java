package com.lazydevs.notification.channel.email.acs;

import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import com.lazydevs.notification.channel.email.acs.AcsEmailProperties.SendMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing of the per-tenant provider properties map.
 */
class AcsEmailPropertiesTest {

    private static final String CONNECTION_STRING =
            "endpoint=https://unit.communication.azure.com/;accesskey=c2VjcmV0LWtleQ==";

    private static Map<String, Object> minimal() {
        Map<String, Object> map = new HashMap<>();
        map.put("connection-string", CONNECTION_STRING);
        map.put("sender", "DoNotReply@example.com");
        return map;
    }

    @Test
    void minimalMap_appliesDefaults() {
        AcsEmailProperties p = AcsEmailProperties.fromMap(minimal());

        assertThat(p.hasConnectionString()).isTrue();
        assertThat(p.sender()).isEqualTo("DoNotReply@example.com");
        assertThat(p.replyTo()).isEmpty();
        assertThat(p.sendMode()).isEqualTo(SendMode.WAIT);
        assertThat(p.waitTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.sdkRetries()).isZero();
        assertThat(p.userEngagementTrackingDisabled()).isNull();
        assertThat(p.credential()).isNull();
    }

    @Test
    void allKeys_areParsed() {
        Map<String, Object> map = new HashMap<>();
        map.put("endpoint", "https://unit.communication.azure.com");
        map.put("credential", "default");
        map.put("sender", " DoNotReply@example.com ");
        map.put("reply-to", "a@example.com, b@example.com");
        map.put("send-mode", "Submit");
        map.put("wait-timeout", "PT5S");
        map.put("sdk-retries", "2");
        map.put("user-engagement-tracking-disabled", "true");
        map.put("from-name", "ignored, unknown keys are fine");

        AcsEmailProperties p = AcsEmailProperties.fromMap(map);

        assertThat(p.endpoint()).isEqualTo("https://unit.communication.azure.com");
        assertThat(p.hasConnectionString()).isFalse();
        assertThat(p.credential()).isEqualTo("default");
        assertThat(p.sender()).isEqualTo("DoNotReply@example.com");
        assertThat(p.replyTo()).containsExactly("a@example.com", "b@example.com");
        assertThat(p.sendMode()).isEqualTo(SendMode.SUBMIT);
        assertThat(p.waitTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.sdkRetries()).isEqualTo(2);
        assertThat(p.userEngagementTrackingDisabled()).isTrue();
    }

    @Test
    void typedValues_areAccepted() {
        Map<String, Object> map = minimal();
        map.put("reply-to", List.of("a@example.com", "b@example.com"));
        map.put("wait-timeout", Duration.ofSeconds(9));
        map.put("sdk-retries", 1);
        map.put("user-engagement-tracking-disabled", false);

        AcsEmailProperties p = AcsEmailProperties.fromMap(map);

        assertThat(p.replyTo()).containsExactly("a@example.com", "b@example.com");
        assertThat(p.waitTimeout()).isEqualTo(Duration.ofSeconds(9));
        assertThat(p.sdkRetries()).isEqualTo(1);
        assertThat(p.userEngagementTrackingDisabled()).isFalse();
    }

    @Test
    void replyTo_indexKeyedMap_keepsIndexOrder() {
        // Shape a YAML list can take after binding into Map<String, Object>.
        Map<String, Object> map = minimal();
        map.put("reply-to", Map.of("1", "second@example.com", "0", "first@example.com"));

        assertThat(AcsEmailProperties.fromMap(map).replyTo())
                .containsExactly("first@example.com", "second@example.com");
    }

    @Test
    void sender_fallsBackToChannelLevelFromAddress() {
        Map<String, Object> map = minimal();
        map.remove("sender");
        map.put("from-address", "noreply@example.com");

        assertThat(AcsEmailProperties.fromMap(map).sender()).isEqualTo("noreply@example.com");
    }

    @Test
    void missingSender_failsWithClearMessage() {
        Map<String, Object> map = minimal();
        map.remove("sender");

        assertThatThrownBy(() -> AcsEmailProperties.fromMap(map))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("'sender' is required");
    }

    @Test
    void missingEndpointAndConnectionString_failsWithClearMessage() {
        assertThatThrownBy(() -> AcsEmailProperties.fromMap(Map.of("sender", "a@example.com")))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("Failed to configure provider 'acs' for channel 'EMAIL'")
                .hasMessageContaining("connection-string")
                .hasMessageContaining("endpoint");
        assertThatThrownBy(() -> AcsEmailProperties.fromMap(null))
                .isInstanceOf(ProviderConfigurationException.class);
    }

    @Test
    void invalidValues_failWithClearMessages() {
        assertInvalid("send-mode", "later", "'send-mode' must be 'wait' or 'submit'");
        assertInvalid("wait-timeout", "60", "ISO-8601 duration");
        assertInvalid("wait-timeout", "PT0S", "must be positive");
        assertInvalid("sdk-retries", "two", "whole number");
        assertInvalid("sdk-retries", "-1", "must not be negative");
        assertInvalid("user-engagement-tracking-disabled", "yes", "true or false");
    }

    @Test
    void toString_masksConnectionString() {
        assertThat(AcsEmailProperties.fromMap(minimal()).toString())
                .doesNotContain("c2VjcmV0LWtleQ")
                .contains("connectionString=****");
    }

    private static void assertInvalid(String key, Object value, String expected) {
        Map<String, Object> map = minimal();
        map.put(key, value);
        assertThatThrownBy(() -> AcsEmailProperties.fromMap(map))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining(expected);
    }
}
