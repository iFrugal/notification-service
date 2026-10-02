package com.lazydevs.notification.channel.push.fcm;

import com.lazydevs.notification.api.delivery.DeliveryEvents;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.channel.push.fcm.FcmStubServer.StubResponse;
import com.lazydevs.notification.channel.push.fcm.FcmTestSupport.RecordingPublisher;
import lazydevs.persistence.connection.multitenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.lazydevs.notification.channel.push.fcm.FcmStubServer.fcmError;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.TOKEN;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.TOKEN_2;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.TOKEN_3;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.configured;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.request;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.tokens;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code deviceTokens}: per-token results, the {@code all} and {@code any} policies,
 * the concurrency bound, and events published after the join.
 */
class FcmMultiTokenTest {

    private final FcmStubServer stub = new FcmStubServer();

    @AfterEach
    void tearDown() {
        stub.close();
        TenantContext.reset();
    }

    private void unregister(String token) {
        stub.respondWith(request -> token.equals(request.target())
                ? StubResponse.of(404, fcmError(404, "NOT_FOUND", "UNREGISTERED", "not found"))
                : null);
    }

    @Test
    void mixedBatch_underPolicyAll_isAmbiguous_withPerTokenResults_andOneEvent() {
        RecordingPublisher publisher = new RecordingPublisher();
        FcmPushProvider provider = configured(stub, Map.of());
        provider.setDeliveryEventPublisher(publisher);
        unregister(TOKEN_2);
        TenantContext.setTenantId("acme");

        SendResult result = provider.send(request(tokens(TOKEN, TOKEN_2, TOKEN_3)), null);

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(FailureType.AMBIGUOUS);
        assertThat(result.errorCode()).isEqualTo("FCM_PARTIAL_FAILURE");
        assertThat(result.messageId()).startsWith("projects/" + FcmStubServer.PROJECT_ID + "/messages/");
        assertThat(result.providerMetadata())
                .containsEntry("fcm.successCount", 2)
                .containsEntry("fcm.failureCount", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.providerMetadata().get("fcm.results");
        assertThat(rows).extracting(row -> row.get("tokenHash")).containsExactly(
                FcmJson.targetHash(TOKEN), FcmJson.targetHash(TOKEN_2), FcmJson.targetHash(TOKEN_3));
        assertThat(rows).extracting(row -> row.get("status")).containsExactly("SENT", "FAILED", "SENT");
        assertThat(rows.get(0).get("name")).isEqualTo(result.messageId());
        assertThat(rows.get(1)).containsEntry("errorCode", "UNREGISTERED").doesNotContainKey("name");
        assertThat(rows.get(2).get("name")).asString().startsWith("projects/");
        assertThat(result.providerMetadata().toString()).doesNotContain(TOKEN, TOKEN_2, TOKEN_3);

        assertThat(publisher.events).singleElement().satisfies(event -> {
            assertThat(event.providerMessageId()).isEqualTo(result.messageId());
            assertThat(event.attributes())
                    .containsEntry(DeliveryEvents.ATTR_TOKEN_HASH, FcmJson.targetHash(TOKEN_2))
                    .containsEntry(DeliveryEvents.ATTR_FAILURE, "UNREGISTERED")
                    .containsEntry(DeliveryEvents.ATTR_ERROR_CODE, "FCM_PARTIAL_FAILURE");
        });
        assertThat(publisher.threads).containsExactly(Thread.currentThread().getName());
        assertThat(publisher.tenants).containsExactly("acme");
    }

    @Test
    void mixedBatch_underPolicyAny_isASuccess_andStillReportsTheDeadToken() {
        RecordingPublisher publisher = new RecordingPublisher();
        FcmPushProvider provider = configured(stub, Map.of("multi-token-policy", "any"));
        provider.setDeliveryEventPublisher(publisher);
        unregister(TOKEN);

        SendResult result = provider.send(request(tokens(TOKEN, TOKEN_2)), null);

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).startsWith("projects/");
        assertThat(result.providerMetadata()).containsEntry("fcm.successCount", 1).containsEntry("fcm.failureCount", 1);
        assertThat(publisher.events).singleElement().satisfies(event -> assertThat(event.attributes())
                .containsEntry(DeliveryEvents.ATTR_ERROR_CODE, "UNREGISTERED")
                .containsEntry(DeliveryEvents.ATTR_TOKEN_HASH, FcmJson.targetHash(TOKEN)));
    }

    @Test
    void every503_isTransient_withTheLongestRetryAfter() {
        FcmPushProvider provider = configured(stub, Map.of());
        stub.respondWith(request -> StubResponse.of(503, fcmError(503, "UNAVAILABLE", "UNAVAILABLE", "busy"))
                .withHeader("Retry-After", TOKEN_2.equals(request.target()) ? "20" : "5"));

        SendResult result = provider.send(request(tokens(TOKEN, TOKEN_2, TOKEN_3)), null);

        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(result.errorCode()).isEqualTo("UNAVAILABLE");
        assertThat(result.messageId()).startsWith("fcm-local:");
        assertThat(result.retryAfter()).contains(Duration.ofSeconds(20));
        assertThat(result.providerMetadata()).containsEntry("fcm.successCount", 0).containsEntry("fcm.failureCount", 3);
    }

    @Test
    void noTokenSent_withAPermanentFailureAmongThem_isPermanent() {
        FcmPushProvider provider = configured(stub, Map.of());
        stub.respondWith(request -> TOKEN.equals(request.target())
                ? StubResponse.of(404, fcmError(404, "NOT_FOUND", "UNREGISTERED", "gone"))
                : StubResponse.of(503, fcmError(503, "UNAVAILABLE", "UNAVAILABLE", "busy")));

        SendResult result = provider.send(request(tokens(TOKEN, TOKEN_2)), null);

        assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(result.errorCode()).isEqualTo("FCM_ALL_TOKENS_FAILED");
    }

    @Test
    void inFlightRequests_neverExceedTheConcurrency() {
        FcmPushProvider provider = configured(stub, Map.of("concurrency", 3));
        stub.respondWith(request -> StubResponse.of(200, FcmStubServer.success("projects/p/messages/x"))
                .after(Duration.ofMillis(100)));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add("token-" + i + "-" + "x".repeat(40));
        }

        SendResult result = provider.send(request(tokens(many.toArray(String[]::new))), null);

        assertThat(result.success()).isTrue();
        assertThat(result.providerMetadata()).containsEntry("fcm.successCount", 12);
        assertThat(stub.maxInFlight()).isBetween(1, 3);
        assertThat(provider.getHealthDetails()).containsEntry("availablePermits", 3);
    }

    @Test
    void noPermitWithinTheTimeout_isTransientConcurrencyLimit() {
        FcmPushProvider provider = configured(stub, Map.of("concurrency", 1, "timeout", "400ms"));
        // Warm up the token so only the send calls hold the single permit.
        assertThat(provider.send(request(FcmTestSupport.token(TOKEN)), null).success()).isTrue();
        stub.respondWith(request -> StubResponse.of(200, FcmStubServer.success("projects/p/messages/slow"))
                .after(Duration.ofMillis(250)));

        SendResult result = provider.send(request(tokens(TOKEN, TOKEN_2, TOKEN_3, "fourth-token-xxxxxxxxxxxx")),
                null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.providerMetadata().get("fcm.results");
        assertThat(rows).anySatisfy(row -> assertThat(row).containsEntry("errorCode", "FCM_CONCURRENCY_LIMIT"));
        assertThat(rows).anySatisfy(row -> assertThat(row).containsEntry("status", "SENT"));
        assertThat(result.failureType()).isEqualTo(FailureType.AMBIGUOUS);
    }
}
