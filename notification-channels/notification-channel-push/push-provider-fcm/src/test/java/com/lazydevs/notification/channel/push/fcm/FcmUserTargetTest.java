package com.lazydevs.notification.channel.push.fcm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.push.PushContent;
import com.lazydevs.notification.api.push.PushTargeting;
import com.lazydevs.notification.channel.push.fcm.FcmStubServer.StubResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.TOKEN;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.TOKEN_2;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.configured;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.request;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.token;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.tokens;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-37 in the FCM provider: an unresolved user target, the per-request multi-token
 * policy, and push template data merged under the caller's data.
 */
class FcmUserTargetTest {

    private final FcmStubServer stub = new FcmStubServer();

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private void unregister(String token) {
        stub.respondWith(request -> token.equals(request.target())
                ? StubResponse.of(404, FcmStubServer.fcmError(404, "NOT_FOUND", "UNREGISTERED", "not found"))
                : null);
    }

    @Test
    void anUnresolvedUserTarget_isRefused_withoutAnHttpCall() {
        SendResult result = configured(stub, Map.of()).send(request(PushRecipient.forUser("u1")), null);

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(result.errorCode()).isEqualTo(FcmMessageMapper.CODE_INVALID_TARGET_SPEC);
        assertThat(result.errorMessage()).contains("push device registry");
        assertThat(stub.sendRequests()).isEmpty();
    }

    @Test
    void aUserTargetWithAToken_isTwoTargets() {
        PushRecipient both = new PushRecipient(null, TOKEN, null, null, null, null, null, null, null, null, null,
                null, null, "u1", null);

        SendResult result = configured(stub, Map.of()).send(request(both), null);

        assertThat(result.errorMessage()).contains("2 targets [deviceToken, userId]");
    }

    @Test
    void theRequestsPolicy_overridesTheTenants() {
        unregister(TOKEN_2);
        FcmPushProvider provider = configured(stub, Map.of()); // tenant policy: all

        SendResult any = provider.send(request(tokens(TOKEN, TOKEN_2),
                Map.of(PushTargeting.MULTI_TOKEN_POLICY_METADATA, "ANY")), null);
        SendResult all = provider.send(request(tokens(TOKEN, TOKEN_2)), null);

        assertThat(any.success()).isTrue();
        assertThat(all.success()).isFalse();
        assertThat(all.failureType()).isEqualTo(FailureType.AMBIGUOUS);
    }

    @Test
    void anUnknownPolicy_isAnInvalidOverride() {
        SendResult result = configured(stub, Map.of()).send(request(tokens(TOKEN, TOKEN_2),
                Map.of(PushTargeting.MULTI_TOKEN_POLICY_METADATA, "most")), null);

        assertThat(result.errorCode()).isEqualTo(FcmMessageMapper.CODE_INVALID_OVERRIDE);
        assertThat(stub.sendRequests()).isEmpty();
    }

    @Test
    void templateData_isMergedUnderTheCallersData_andTheTitleComesFromTheTemplate() {
        Map<String, String> rendered = new LinkedHashMap<>();
        rendered.put("orderId", "from-template");
        rendered.put("deepLink", "app://orders/42");
        RenderedContent content = new RenderedContent("Rendered title", "Rendered body", null, "SHIPPED",
                new PushContent("Rendered title", "Rendered body", rendered));
        PushRecipient recipient = new PushRecipient(null, TOKEN, null, null, "Recipient title", null,
                Map.of("orderId", "from-caller"), null, null, null, null);

        SendResult result = configured(stub, Map.of()).send(request(recipient), content);

        assertThat(result.success()).isTrue();
        ObjectNode message = stub.sendRequests().getLast().message();
        assertThat(message.at("/notification/title").asText()).isEqualTo("Rendered title");
        assertThat(message.at("/notification/body").asText()).isEqualTo("Rendered body");
        assertThat(message.at("/data/orderId").asText()).isEqualTo("from-caller");
        assertThat(message.at("/data/deepLink").asText()).isEqualTo("app://orders/42");
    }

    @Test
    void reservedKeysFromTheTemplate_areRefused() {
        RenderedContent content = new RenderedContent(null, "b", null, "T",
                new PushContent(null, "b", Map.of("google.sent_time", "x")));

        SendResult result = configured(stub, Map.of()).send(request(token(TOKEN)), content);

        assertThat(result.errorCode()).isEqualTo(FcmMessageMapper.CODE_INVALID_DATA_KEY);
    }
}
