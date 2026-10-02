package com.lazydevs.notification.channel.push.fcm;

import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEvents;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.channel.push.fcm.FcmStubServer.StubResponse;
import com.lazydevs.notification.channel.push.fcm.FcmTestSupport.MutableClock;
import com.lazydevs.notification.channel.push.fcm.FcmTestSupport.RecordingPublisher;
import lazydevs.persistence.connection.multitenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Stream;

import static com.lazydevs.notification.channel.push.fcm.FcmStubServer.fcmError;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.FID;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.TOKEN;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.configured;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * One row per line of the classification table in {@link FcmErrorClassifier},
 * driven end to end: provider, JDK transport, loopback stub.
 */
class FcmErrorClassificationTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final FcmStubServer stub = new FcmStubServer(clock);

    @AfterEach
    void tearDown() {
        stub.close();
        TenantContext.reset();
    }

    static Stream<Arguments> table() {
        String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.plusSeconds(120).atOffset(ZoneOffset.UTC));
        return Stream.of(
                Arguments.of("400 INVALID_ARGUMENT on message.token",
                        StubResponse.of(400, fcmError(400, "INVALID_ARGUMENT", "INVALID_ARGUMENT",
                                "The registration token is not a valid FCM registration token", "message.token")),
                        FailureType.PERMANENT, "INVALID_ARGUMENT", null, true),
                Arguments.of("400 INVALID_ARGUMENT on another field",
                        StubResponse.of(400, fcmError(400, "INVALID_ARGUMENT", "INVALID_ARGUMENT",
                                "Invalid value at 'message.android.ttl'", "message.android.ttl")),
                        FailureType.PERMANENT, "INVALID_ARGUMENT", null, false),
                Arguments.of("400 without FcmError",
                        StubResponse.of(400, fcmError(400, "INVALID_ARGUMENT", null, "Request contains an invalid"
                                + " argument.")),
                        FailureType.PERMANENT, "INVALID_ARGUMENT", null, false),
                Arguments.of("404 UNREGISTERED",
                        StubResponse.of(404, fcmError(404, "NOT_FOUND", "UNREGISTERED",
                                "Requested entity was not found.")),
                        FailureType.PERMANENT, "UNREGISTERED", null, true),
                Arguments.of("403 SENDER_ID_MISMATCH",
                        StubResponse.of(403, fcmError(403, "PERMISSION_DENIED", "SENDER_ID_MISMATCH",
                                "SenderId mismatch")),
                        FailureType.PERMANENT, "SENDER_ID_MISMATCH", null, true),
                Arguments.of("403 without FcmError (IAM)",
                        StubResponse.of(403, fcmError(403, "PERMISSION_DENIED", null,
                                "Permission 'cloudmessaging.messages.create' denied")),
                        FailureType.PERMANENT, "PERMISSION_DENIED", null, false),
                Arguments.of("429 QUOTA_EXCEEDED with Retry-After: 1",
                        StubResponse.of(429, fcmError(429, "RESOURCE_EXHAUSTED", "QUOTA_EXCEEDED",
                                "Quota exceeded")).withHeader("Retry-After", "1"),
                        FailureType.TRANSIENT, "QUOTA_EXCEEDED", Duration.ofSeconds(1), false),
                Arguments.of("429 QUOTA_EXCEEDED without Retry-After",
                        StubResponse.of(429, fcmError(429, "RESOURCE_EXHAUSTED", "QUOTA_EXCEEDED",
                                "Quota exceeded")),
                        FailureType.TRANSIENT, "QUOTA_EXCEEDED", Duration.ofSeconds(60), false),
                Arguments.of("429 with an HTTP-date Retry-After",
                        StubResponse.of(429, fcmError(429, "RESOURCE_EXHAUSTED", "QUOTA_EXCEEDED",
                                "Quota exceeded")).withHeader("Retry-After", httpDate),
                        FailureType.TRANSIENT, "QUOTA_EXCEEDED", Duration.ofSeconds(120), false),
                Arguments.of("429 without FcmError",
                        StubResponse.of(429, fcmError(429, "RESOURCE_EXHAUSTED", null, "overloaded")),
                        FailureType.TRANSIENT, "RESOURCE_EXHAUSTED", Duration.ofSeconds(60), false),
                Arguments.of("503 UNAVAILABLE with Retry-After",
                        StubResponse.of(503, fcmError(503, "UNAVAILABLE", "UNAVAILABLE", "The service is"
                                + " currently unavailable.")).withHeader("Retry-After", "30"),
                        FailureType.TRANSIENT, "UNAVAILABLE", Duration.ofSeconds(30), false),
                Arguments.of("503 UNAVAILABLE without Retry-After",
                        StubResponse.of(503, fcmError(503, "UNAVAILABLE", "UNAVAILABLE", "unavailable")),
                        FailureType.TRANSIENT, "UNAVAILABLE", null, false),
                Arguments.of("500 INTERNAL",
                        StubResponse.of(500, fcmError(500, "INTERNAL", "INTERNAL", "Internal error")),
                        FailureType.TRANSIENT, "INTERNAL", null, false),
                Arguments.of("401 THIRD_PARTY_AUTH_ERROR",
                        StubResponse.of(401, fcmError(401, "UNAUTHENTICATED", "THIRD_PARTY_AUTH_ERROR",
                                "Auth error from APNS or Web Push Service")),
                        FailureType.PERMANENT, "THIRD_PARTY_AUTH_ERROR", null, false),
                Arguments.of("400 UNSPECIFIED_ERROR",
                        StubResponse.of(400, fcmError(400, "INVALID_ARGUMENT", "UNSPECIFIED_ERROR", "unspecified")),
                        FailureType.UNKNOWN, "UNSPECIFIED_ERROR", null, false),
                Arguments.of("unparsable 4xx",
                        StubResponse.of(400, "<html>Bad Request</html>"),
                        FailureType.UNKNOWN, "HTTP_400", null, false),
                Arguments.of("unparsable 5xx",
                        StubResponse.of(502, "<html>Bad Gateway</html>"),
                        FailureType.TRANSIENT, "HTTP_502", null, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    void classificationTable(String row, StubResponse response, FailureType type, String code, Duration retryAfter,
                             boolean invalidTargetEvent) {
        RecordingPublisher publisher = new RecordingPublisher();
        FcmPushProvider provider = configured(stub, Map.of(), clock);
        provider.setDeliveryEventPublisher(publisher);
        stub.enqueueSend(response);

        SendResult result = provider.send(request(FcmTestSupport.token(TOKEN)), null);

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).as(row).isEqualTo(type);
        assertThat(result.errorCode()).as(row).isEqualTo(code);
        assertThat(result.retryAfter()).as(row).isEqualTo(java.util.Optional.ofNullable(retryAfter));
        assertThat(result.messageId()).startsWith("fcm-local:");
        assertThat(publisher.events).as(row).hasSize(invalidTargetEvent ? 1 : 0);
        assertThat(stub.sendRequests()).as("no inline resend for " + row).hasSize(1);
    }

    @Test
    void retryAfterHint_isAnIsoDurationInTheMetadata() {
        FcmPushProvider provider = configured(stub, Map.of(), clock);
        stub.enqueueSend(StubResponse.of(429, fcmError(429, "RESOURCE_EXHAUSTED", "QUOTA_EXCEEDED", "Quota")));

        SendResult result = provider.send(request(FcmTestSupport.token(TOKEN)), null);

        assertThat(result.providerMetadata()).containsEntry(SendResult.RETRY_AFTER_METADATA_KEY, "PT1M");
    }

    @Test
    void unregistered_publishesBouncedInvalidTargetEvent_onTheCallingThread_withTheTenant() {
        RecordingPublisher publisher = new RecordingPublisher();
        FcmPushProvider provider = configured(stub, Map.of(), clock);
        provider.setDeliveryEventPublisher(publisher);
        stub.enqueueSend(StubResponse.of(404, fcmError(404, "NOT_FOUND", "UNREGISTERED", "not found")));
        TenantContext.setTenantId("acme");

        SendResult result = provider.send(request(FcmTestSupport.token(TOKEN)), null);

        String tokenHash = FcmJson.targetHash(TOKEN);
        assertThat(tokenHash).matches("sha256:[0-9a-f]{16}");
        assertThat(publisher.events).singleElement().satisfies(event -> {
            assertThat(event.status()).isEqualTo(DeliveryStatus.BOUNCED);
            assertThat(event.reason()).isEqualTo(DeliveryEvents.REASON_INVALID_TARGET);
            assertThat(event.providerName()).isEqualTo("fcm");
            assertThat(event.providerMessageId()).isEqualTo(result.messageId()).startsWith("fcm-local:");
            assertThat(event.providerEventId()).isEqualTo(
                    FcmJson.sha256Hex(result.messageId() + "|" + tokenHash + "|BOUNCED"));
            assertThat(event.timestamp()).isEqualTo(NOW);
            assertThat(event.attributes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    DeliveryEvents.ATTR_FAILURE, "UNREGISTERED",
                    DeliveryEvents.ATTR_ERROR_CODE, "UNREGISTERED",
                    DeliveryEvents.ATTR_TARGET_TYPE, DeliveryEvents.TARGET_TYPE_TOKEN,
                    DeliveryEvents.ATTR_TOKEN_HASH, tokenHash));
            assertThat(event.toString()).doesNotContain(TOKEN);
        });
        assertThat(publisher.tenants).containsExactly("acme");
        assertThat(publisher.threads).containsExactly(Thread.currentThread().getName());
    }

    @Test
    void unregisteredFid_eventHasTargetTypeFid() {
        RecordingPublisher publisher = new RecordingPublisher();
        FcmPushProvider provider = configured(stub, Map.of(), clock);
        provider.setDeliveryEventPublisher(publisher);
        stub.enqueueSend(StubResponse.of(404, fcmError(404, "NOT_FOUND", "UNREGISTERED", "not found")));

        provider.send(request(FcmTestSupport.fid(FID)), null);

        DeliveryEvent event = publisher.events.getFirst();
        assertThat(event.attributes()).containsEntry(DeliveryEvents.ATTR_TARGET_TYPE, DeliveryEvents.TARGET_TYPE_FID)
                .containsEntry(DeliveryEvents.ATTR_TOKEN_HASH, FcmJson.targetHash(FID));
    }

    @Test
    void unregisteredTopic_publishesNoEvent() {
        RecordingPublisher publisher = new RecordingPublisher();
        FcmPushProvider provider = configured(stub, Map.of(), clock);
        provider.setDeliveryEventPublisher(publisher);
        stub.enqueueSend(StubResponse.of(404, fcmError(404, "NOT_FOUND", "UNREGISTERED", "not found")));

        assertThat(provider.send(request(FcmTestSupport.topic("news")), null).failureType())
                .isEqualTo(FailureType.PERMANENT);
        assertThat(publisher.events).isEmpty();
    }

    @Test
    void unauthorizedWithoutFcmError_invalidatesTheToken_andResendsOnce() {
        FcmPushProvider provider = configured(stub, Map.of(), clock);
        assertThat(provider.send(request(FcmTestSupport.token(TOKEN)), null).success()).isTrue();
        stub.revokeTokens();

        SendResult result = provider.send(request(FcmTestSupport.token(TOKEN)), null);

        assertThat(result.success()).isTrue();
        assertThat(stub.tokenCalls()).isEqualTo(2);
        assertThat(stub.sendRequests()).hasSize(3);
        assertThat(stub.sendRequests().get(1).authorization()).isNotEqualTo(stub.sendRequests().get(2).authorization());
    }

    @Test
    void unauthorizedWithoutFcmError_twice_isTransient() {
        FcmPushProvider provider = configured(stub, Map.of(), clock);
        String unauthenticated = fcmError(401, "UNAUTHENTICATED", null, "Request had invalid credentials.");
        stub.enqueueSend(StubResponse.of(401, unauthenticated), StubResponse.of(401, unauthenticated));

        SendResult result = provider.send(request(FcmTestSupport.token(TOKEN)), null);

        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(result.errorCode()).isEqualTo("UNAUTHENTICATED");
        assertThat(stub.sendRequests()).hasSize(2);
        assertThat(stub.tokenCalls()).isEqualTo(2);
    }

    @Test
    void timeoutAfterTheRequestWasSent_isAmbiguous() {
        FcmPushProvider provider = configured(stub, Map.of("timeout", "300ms"), clock);
        stub.enqueueSend(StubResponse.of(200, FcmStubServer.success("projects/p/messages/late"))
                .after(Duration.ofSeconds(2)));

        SendResult result = provider.send(request(FcmTestSupport.token(TOKEN)), null);

        assertThat(result.failureType()).isEqualTo(FailureType.AMBIGUOUS);
        assertThat(result.errorCode()).isEqualTo("FCM_TIMEOUT");
        assertThat(result.errorMessage()).contains("may have accepted");
        assertThat(result.messageId()).startsWith("fcm-local:");
    }

    @Test
    void timeoutAfterTheRequestWasSent_withTheTransientOptOut_isTransient() {
        FcmPushProvider provider = configured(stub, Map.of("timeout", "300ms", "timeout-classification", "transient"),
                clock);
        stub.enqueueSend(StubResponse.of(200, FcmStubServer.success("projects/p/messages/late"))
                .after(Duration.ofSeconds(2)));

        SendResult result = provider.send(request(FcmTestSupport.token(TOKEN)), null);

        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(result.errorCode()).isEqualTo("FCM_TIMEOUT");
    }

    @Test
    void connectionRefused_isTransient_becauseNothingWasSent() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        FcmPushProvider provider = FcmPushProvider.withTransport(
                FcmSettings.fromMap(Map.of("project-id", "p", "endpoint", "http://127.0.0.1:" + closedPort)),
                JdkFcmHttpTransport.shared(), staticToken());

        SendResult result = provider.send(request(FcmTestSupport.token(TOKEN)), null);

        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(result.errorCode()).isEqualTo("FCM_CONNECT_FAILED");
    }

    static FcmAccessTokenProvider staticToken() {
        return new FcmAccessTokenProvider() {
            @Override
            public FcmAccessToken token() {
                return new FcmAccessToken("static-test-token", Instant.now().plusSeconds(3600));
            }

            @Override
            public void invalidate() {
                // nothing cached
            }

            @Override
            public java.util.Optional<String> projectId() {
                return java.util.Optional.empty();
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
    }
}
