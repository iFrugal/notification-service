package com.lazydevs.notification.api.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.util.PiiMasking;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The 1.4 user target of {@link PushRecipient} (DD-37).
 */
class PushRecipientUserTargetTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void forUser_namesOnlyTheUser() {
        PushRecipient user = PushRecipient.forUser("u1", "recruiter");

        assertThat(user.isUserTarget()).isTrue();
        assertThat(user.userId()).isEqualTo("u1");
        assertThat(user.appId()).isEqualTo("recruiter");
        assertThat(user.deviceToken()).isNull();
        assertThat(user.deviceTokens()).isNull();
        assertThatThrownBy(() -> PushRecipient.forUser(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withDeviceTokens_replacesTheUserTarget_andKeepsTheContent() {
        PushRecipient user = new PushRecipient("r-1", null, null, null, "Hi", "There", Map.of("k", "v"), 1, "default",
                "https://img", "app://open", null, null, "u1", "app");

        PushRecipient tokens = user.withDeviceTokens(List.of("t1", "t2"));

        assertThat(tokens).isEqualTo(new PushRecipient("r-1", null, null, null, "Hi", "There", Map.of("k", "v"), 1,
                "default", "https://img", "app://open", null, List.of("t1", "t2"), null, null));
        assertThat(tokens.isUserTarget()).isFalse();
        assertThatThrownBy(() -> user.withDeviceTokens(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void json_roundTripsTheUserTarget_andOmitsItWhenUnset() throws Exception {
        String written = json.writeValueAsString((Recipient) PushRecipient.forUser("u1", "app"));
        assertThat(written).contains("\"userId\":\"u1\"", "\"appId\":\"app\"").doesNotContain("userTarget");
        assertThat(json.readValue(written, Recipient.class)).isEqualTo(PushRecipient.forUser("u1", "app"));

        JsonNode token = json.readTree(json.writeValueAsString(
                new PushRecipient(null, "t", null, null, null, null, null, null, null, null, null)));
        assertThat(token.has("userId")).isFalse();
        assertThat(token.has("appId")).isFalse();
    }

    @Test
    void masking_showsTheUserMasked() {
        assertThat(PiiMasking.mask(PushRecipient.forUser("user-12345678"))).isEqualTo("user=***5678");
        assertThat(PiiMasking.mask(PushRecipient.forUser("short"))).isEqualTo("user=***");
    }
}
