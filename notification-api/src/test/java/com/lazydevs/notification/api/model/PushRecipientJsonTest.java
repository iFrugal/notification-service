package com.lazydevs.notification.api.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PushRecipient} JSON across the 1.1 and 1.2 shapes. A 1.1 reader
 * fails on unknown properties by default, so a recipient that does not use
 * the 1.2 fields must serialize without them.
 */
class PushRecipientJsonTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void json11Shape_readsWithoutTheNewFields() throws Exception {
        Recipient recipient = json.readValue("""
                {"type":"PUSH","id":"r-1","deviceToken":"token-1","topic":null,"condition":null,\
                "title":"Hi","body":"There","data":{"k":"v"},"badge":2,"sound":"default",\
                "imageUrl":null,"clickAction":"app://open"}""", Recipient.class);

        assertThat(recipient).isEqualTo(new PushRecipient("r-1", "token-1", null, null, "Hi", "There",
                Map.of("k", "v"), 2, "default", null, "app://open"));
        PushRecipient push = (PushRecipient) recipient;
        assertThat(push.fid()).isNull();
        assertThat(push.deviceTokens()).isNull();
    }

    @Test
    void recipientWithoutTheNewFields_writesThe11Shape() throws Exception {
        PushRecipient push = new PushRecipient("r-1", "token-1", null, null, "Hi", "There",
                null, null, null, null, null);
        PushRecipient emptyTokens = new PushRecipient("r-1", "token-1", null, null, "Hi", "There",
                null, null, null, null, null, null, List.of());

        for (PushRecipient value : List.of(push, emptyTokens)) {
            JsonNode node = json.readTree(json.writeValueAsString(value));
            assertThat(node.has("fid")).isFalse();
            assertThat(node.has("deviceTokens")).isFalse();
            assertThat(node.get("deviceToken").asText()).isEqualTo("token-1");
        }
    }

    @Test
    void json12Shape_roundTrips() throws Exception {
        PushRecipient push = new PushRecipient(null, null, null, null, "Hi", "There",
                Map.of("k", "v"), null, null, null, null, "fid-abcdefghijklmnop", List.of("t-1", "t-2"));

        String written = json.writeValueAsString((Recipient) push);
        Recipient read = json.readValue(written, Recipient.class);

        assertThat(written).contains("\"type\":\"PUSH\"", "\"fid\":\"fid-abcdefghijklmnop\"",
                "\"deviceTokens\":[\"t-1\",\"t-2\"]");
        assertThat(read).isEqualTo(push);
    }

    @Test
    void json12Shape_readsFromHandWrittenJson() throws Exception {
        PushRecipient push = (PushRecipient) json.readValue("""
                {"type":"PUSH","title":"Hi","body":"There","deviceTokens":["t-1","t-2","t-3"]}""",
                Recipient.class);

        assertThat(push.deviceTokens()).containsExactly("t-1", "t-2", "t-3");
        assertThat(push.deviceToken()).isNull();
        assertThat(push.fid()).isNull();
    }
}
