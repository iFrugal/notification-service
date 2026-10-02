package com.lazydevs.notification.channel.push.fcm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.api.model.SendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.FID;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.TOKEN;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.configured;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden wire JSON per target type, the platform mappings and overrides, reserved
 * data keys and payload size limits, as the stub FCM endpoint receives them.
 */
class FcmMessageMappingTest {

    private final FcmStubServer stub = new FcmStubServer();

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private String sentJson(FcmPushProvider provider, PushRecipient recipient, Map<String, String> metadata,
                            RenderedContent content) {
        SendResult result = provider.send(request(recipient, metadata), content);
        assertThat(result.success()).as("send result %s", result).isTrue();
        List<FcmStubServer.SendRequest> requests = stub.sendRequests();
        return FcmJson.writeString(requests.getLast().body());
    }

    private static String golden(String json) throws Exception {
        // Re-serialized, so the comparison is exact, field order included.
        return FcmJson.writeString(FcmJson.MAPPER.readTree(json));
    }

    @Test
    void token_withAndroidAndApnsOverrides_andTenantDefaults() throws Exception {
        FcmPushProvider provider = configured(stub, Map.of("android.notification.channel_id", "orders"));
        PushRecipient recipient = new PushRecipient(null, TOKEN, null, null, "Your order shipped",
                "Recipient body", Map.of("orderId", "42"), 3, "default", "https://cdn.example.com/box.png",
                "OPEN_ORDER");
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("campaign", "autumn");
        metadata.put("fcm.android.priority", "high");
        metadata.put("fcm.android.ttl", "3600s");
        metadata.put("fcm.android.collapse_key", "order-42");
        metadata.put("fcm.apns.headers.apns-priority", "10");
        metadata.put("fcm.apns.headers.apns-collapse-id", "order-42");
        metadata.put("fcm.apns.headers.apns-expiration", "1767225600");

        String json = sentJson(provider, recipient, metadata, RenderedContent.text("Arrives Tuesday"));

        assertThat(json).isEqualTo(golden("""
                {"message":{
                  "token":"%s",
                  "notification":{"title":"Your order shipped","body":"Arrives Tuesday",
                                  "image":"https://cdn.example.com/box.png"},
                  "data":{"orderId":"42"},
                  "android":{"notification":{"channel_id":"orders","sound":"default","click_action":"OPEN_ORDER"},
                             "priority":"HIGH","ttl":"3600s","collapse_key":"order-42"},
                  "apns":{"payload":{"aps":{"badge":3,"sound":"default","category":"OPEN_ORDER"}},
                          "headers":{"apns-priority":"10","apns-collapse-id":"order-42",
                                     "apns-expiration":"1767225600"}}
                }}""".formatted(TOKEN)));
        assertThat(stub.sendRequests().getLast().path())
                .isEqualTo("/v1/projects/" + FcmStubServer.PROJECT_ID + "/messages:send");
        assertThat(stub.sendRequests().getLast().contentType()).isEqualTo("application/json; charset=UTF-8");
    }

    @Test
    void fid_target_usesTheFidField() throws Exception {
        String json = sentJson(configured(stub, Map.of()), FcmTestSupport.fid(FID), null, null);

        assertThat(json).isEqualTo(golden("""
                {"message":{"fid":"%s","notification":{"title":"Title","body":"Body"}}}""".formatted(FID)));
    }

    @Test
    void topic_target_stripsTheTopicsPrefix_andWebpushLinkNeedsHttps() throws Exception {
        PushRecipient recipient = new PushRecipient(null, null, "/topics/news", null, null, null,
                Map.of("k", "v"), null, null, null, "https://example.com/news/1");

        String json = sentJson(configured(stub, Map.of()), recipient, Map.of("fcm.webpush.headers.TTL", "300"), null);

        assertThat(json).isEqualTo(golden("""
                {"message":{"topic":"news","data":{"k":"v"},
                  "android":{"notification":{"click_action":"https://example.com/news/1"}},
                  "apns":{"payload":{"aps":{"category":"https://example.com/news/1"}}},
                  "webpush":{"fcm_options":{"link":"https://example.com/news/1"},"headers":{"TTL":"300"}}}}"""));
    }

    @Test
    void condition_target_andNonHttpsClickAction_hasNoWebpushLink() throws Exception {
        PushRecipient recipient = new PushRecipient(null, null, null, "'a' in topics && 'b' in topics",
                "T", null, null, null, null, null, "OPEN_APP");

        String json = sentJson(configured(stub, Map.of()), recipient, null, null);

        assertThat(json).isEqualTo(golden("""
                {"message":{"condition":"'a' in topics && 'b' in topics","notification":{"title":"T"},
                  "android":{"notification":{"click_action":"OPEN_APP"}},
                  "apns":{"payload":{"aps":{"category":"OPEN_APP"}}}}}"""));
    }

    @Test
    void deviceTokens_sendOneAddressedMessagePerToken() {
        FcmPushProvider provider = configured(stub, Map.of());

        provider.send(request(FcmTestSupport.tokens(TOKEN, FcmTestSupport.TOKEN_2, TOKEN)), null);

        assertThat(stub.targets()).containsExactlyInAnyOrder(TOKEN, FcmTestSupport.TOKEN_2);
        assertThat(stub.sendRequests()).allSatisfy(r -> assertThat(r.message().get("notification").toString())
                .isEqualTo("{\"title\":\"Title\",\"body\":\"Body\"}"));
    }

    @Test
    void renderedSubjectAndText_winOverRecipientTitleAndBody() {
        FcmPushProvider provider = configured(stub, Map.of());

        provider.send(request(FcmTestSupport.token(TOKEN)), new RenderedContent("Rendered title", "Rendered body",
                null, "ORDER_SHIPPED"));

        ObjectNode notification = (ObjectNode) stub.sendRequests().getLast().message().get("notification");
        assertThat(notification.get("title").asText()).isEqualTo("Rendered title");
        assertThat(notification.get("body").asText()).isEqualTo("Rendered body");
    }

    @Test
    void tenantDefaults_asNestedMaps_areMergedUnderTheRequestFields() throws Exception {
        FcmPushProvider provider = configured(stub, Map.of(
                "android", Map.of("priority", "normal", "ttl", "60s"),
                "apns", Map.of("headers", Map.of("apns-priority", 5)),
                "webpush", Map.of("headers", Map.of("Urgency", "high"))));

        provider.send(request(FcmTestSupport.token(TOKEN), Map.of("fcm.android.priority", "HIGH")), null);

        ObjectNode message = stub.sendRequests().getLast().message();
        // Tree equality: the order of Map.of entries is not defined.
        assertThat(message.get("android")).isEqualTo(FcmJson.MAPPER.readTree("{\"priority\":\"HIGH\",\"ttl\":\"60s\"}"));
        assertThat(message.get("apns")).isEqualTo(FcmJson.MAPPER.readTree("{\"headers\":{\"apns-priority\":\"5\"}}"));
        assertThat(message.get("webpush")).isEqualTo(FcmJson.MAPPER.readTree("{\"headers\":{\"Urgency\":\"high\"}}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"from", "message_type", "google.c.a.e", "GOOGLE.sender", "gcm.notification.title",
            "gcm.n.e"})
    void reservedDataKeys_failPermanently_withoutCallingFcm(String key) {
        PushRecipient recipient = new PushRecipient(null, TOKEN, null, null, "T", "B", Map.of(key, "x"),
                null, null, null, null);

        SendResult result = configured(stub, Map.of()).send(request(recipient), null);

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(result.errorCode()).isEqualTo("FCM_INVALID_DATA_KEY");
        assertThat(result.errorMessage()).contains(key);
        assertThat(stub.sendRequests()).isEmpty();
        assertThat(stub.tokenCalls()).isZero();
    }

    @Test
    void collapseKeyAsData_isAllowed() {
        PushRecipient recipient = new PushRecipient(null, TOKEN, null, null, "T", "B",
                Map.of("collapse_key", "x", "fromCity", "Pune"), null, null, null, null);

        assertThat(configured(stub, Map.of()).send(request(recipient), null).success()).isTrue();
    }

    @ParameterizedTest(name = "{0} target, {1} payload bytes -> accepted={2}")
    @CsvSource({
            "token, 4096, true",
            "token, 4097, false",
            "fid, 4096, true",
            "fid, 4097, false",
            "topic, 2048, true",
            "topic, 2049, false",
            "condition, 2048, true",
            "condition, 2049, false"})
    void payloadSizeLimits(String targetType, int payloadBytes, boolean accepted) {
        // Payload = data keys and values + notification title, body and image (UTF-8 bytes).
        String title = "é".repeat(50); // 100 bytes in UTF-8
        String key = "k";
        String value = "v".repeat(payloadBytes - 100 - key.length());
        PushRecipient recipient = switch (targetType) {
            case "token" -> new PushRecipient(null, TOKEN, null, null, title, null, Map.of(key, value), null,
                    null, null, null);
            case "fid" -> new PushRecipient(null, null, null, null, title, null, Map.of(key, value), null, null,
                    null, null, FID, null);
            case "topic" -> new PushRecipient(null, null, "news", null, title, null, Map.of(key, value), null,
                    null, null, null);
            default -> new PushRecipient(null, null, null, "'a' in topics", title, null, Map.of(key, value),
                    null, null, null, null);
        };

        SendResult result = configured(stub, Map.of()).send(request(recipient), null);

        if (accepted) {
            assertThat(result.success()).as("%s", result).isTrue();
        } else {
            assertThat(result.success()).isFalse();
            assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
            assertThat(result.errorCode()).isEqualTo("FCM_PAYLOAD_TOO_LARGE");
            assertThat(result.errorMessage()).contains(payloadBytes + " bytes");
            assertThat(stub.sendRequests()).isEmpty();
        }
    }

    @Test
    void noTarget_orTwoTargets_isAnInvalidTargetSpec() {
        FcmPushProvider provider = configured(stub, Map.of());
        PushRecipient none = new PushRecipient(null, " ", null, null, "T", "B", null, null, null, null, null);
        PushRecipient two = new PushRecipient(null, TOKEN, "news", null, "T", "B", null, null, null, null, null);
        PushRecipient blankInList = new PushRecipient(null, null, null, null, "T", "B", null, null, null, null,
                null, null, List.of(TOKEN, " "));

        for (PushRecipient recipient : List.of(none, two, blankInList)) {
            SendResult result = provider.send(request(recipient), null);
            assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
            assertThat(result.errorCode()).isEqualTo("FCM_INVALID_TARGET_SPEC");
            assertThat(result.errorMessage()).doesNotContain(TOKEN);
        }
        assertThat(stub.sendRequests()).isEmpty();
    }

    @Test
    void moreTokensThanMaxTokens_failPermanently() {
        FcmPushProvider provider = configured(stub, Map.of("max-tokens", 2));

        SendResult result = provider.send(request(FcmTestSupport.tokens(TOKEN, FcmTestSupport.TOKEN_2,
                FcmTestSupport.TOKEN_3)), null);

        assertThat(result.errorCode()).isEqualTo("FCM_TOO_MANY_TOKENS");
        assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(stub.sendRequests()).isEmpty();
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource(delimiter = '|', value = {
            "fcm.android.priority | urgent",
            "fcm.android.ttl | 3600",
            "fcm.android.ttl | 1.5m",
            "fcm.android.ttl | 2419201s",
            "fcm.apns.headers.apns-priority | 7",
            "fcm.apns.headers.apns-expiration | tomorrow",
            "fcm.webpush.headers.TTL | 1h",
            "fcm.android.restricted_package_name | com.example",
            "fcm.apns.headers.apns-collapse-id | 0123456789012345678901234567890123456789012345678901234567890123x"})
    void invalidOrUnknownOverrides_failPermanently(String key, String value) {
        SendResult result = configured(stub, Map.of())
                .send(request(FcmTestSupport.token(TOKEN), Map.of(key, value)), null);

        assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(result.errorCode()).isEqualTo("FCM_INVALID_OVERRIDE");
        assertThat(result.errorMessage()).contains(key);
        assertThat(stub.sendRequests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "3s", "3.5s", "3.000000001s", "2419200s"})
    void validTtlSyntax(String ttl) {
        assertThat(FcmMessageMapper.isValidTtl(ttl)).isTrue();
    }
}
