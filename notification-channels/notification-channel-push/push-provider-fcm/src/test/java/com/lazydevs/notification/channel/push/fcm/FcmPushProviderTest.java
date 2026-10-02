package com.lazydevs.notification.channel.push.fcm;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.delivery.DeliveryEventPublisher;
import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.TOKEN;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.configured;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lifecycle, the fqcn path, dry-run and validate-only, misconfiguration messages,
 * the test seam and health details.
 */
class FcmPushProviderTest {

    private final FcmStubServer stub = new FcmStubServer();

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private Map<String, Object> props(Object... keyValues) {
        Map<String, Object> props = new LinkedHashMap<>(stub.properties());
        for (int i = 0; i < keyValues.length; i += 2) {
            props.put((String) keyValues[i], keyValues[i + 1]);
        }
        return props;
    }

    @Test
    void fqcnPath_noArgConstructor_configureInitSend_againstTheStub() {
        FcmPushProvider provider = new FcmPushProvider();
        assertThat(provider.getChannel()).isEqualTo(Channel.PUSH);
        assertThat(provider.getProviderName()).isEqualTo("fcm");
        assertThat(provider.factories()).filteredOn(ServiceAccountJwtTokenProviderFactory.class::isInstance)
                .hasSize(1);
        assertThat(provider.transport()).isNull();

        provider.configure(stub.properties());
        provider.init();
        assertThat(stub.tokenCalls()).as("configure and init make no network call").isZero();
        assertThat(provider.transport()).isSameAs(JdkFcmHttpTransport.shared());
        assertThat(provider.projectId()).as("from project_id of the key").isEqualTo(FcmStubServer.PROJECT_ID);

        SendResult result = provider.send(request(FcmTestSupport.token(TOKEN)), null);

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("projects/" + FcmStubServer.PROJECT_ID + "/messages/1");
        assertThat(stub.tokenCalls()).isEqualTo(1);
    }

    @Test
    void projectIdSetting_winsOverTheKey() {
        FcmPushProvider provider = configured(stub, Map.of("project-id", "other-project"));

        provider.send(request(FcmTestSupport.token(TOKEN)), null);

        assertThat(stub.sendRequests().getFirst().path()).isEqualTo("/v1/projects/other-project/messages:send");
    }

    @Test
    void dryRun_makesNoHttpCall_butStillValidatesTheMessage() {
        FcmPushProvider provider = configured(stub, Map.of("dry-run", "true"));

        SendResult ok = provider.send(request(FcmTestSupport.token(TOKEN)), null);
        SendResult bad = provider.send(request(new com.lazydevs.notification.api.model.PushRecipient(null, TOKEN,
                null, null, "T", "B", Map.of("from", "x"), null, null, null, null)), null);

        assertThat(ok.success()).isTrue();
        assertThat(ok.messageId()).matches("dry-run:[0-9a-f-]{36}");
        assertThat(ok.providerMetadata()).containsEntry("fcm.dryRun", true);
        assertThat(bad.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(stub.tokenCalls()).isZero();
        assertThat(stub.sendRequests()).isEmpty();
    }

    @Test
    void validateOnly_sendsValidateOnlyTrue() {
        FcmPushProvider provider = configured(stub, Map.of("validate-only", true));
        stub.enqueueSend(FcmStubServer.StubResponse.of(200,
                FcmStubServer.success("projects/stub-project/messages/fake_message_id")));

        SendResult result = provider.send(request(FcmTestSupport.token(TOKEN)), null);

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).endsWith("fake_message_id");
        assertThat(result.providerMetadata()).containsEntry("fcm.validateOnly", true);
        assertThat(stub.sendRequests().getFirst().body().get("validate_only").asBoolean()).isTrue();
        assertThat(FcmJson.writeString(stub.sendRequests().getFirst().body())).startsWith("{\"validate_only\":true,");
    }

    @Test
    void withoutValidateOnly_theFieldIsAbsent() {
        configured(stub, Map.of()).send(request(FcmTestSupport.token(TOKEN)), null);

        assertThat(stub.sendRequests().getFirst().body().has("validate_only")).isFalse();
    }

    @Test
    void deprecatedCredentialsPath_stillWorks(@TempDir Path dir) throws Exception {
        Path key = dir.resolve("firebase.json");
        Files.writeString(key, stub.serviceAccountJson());
        FcmPushProvider provider = new FcmPushProvider();

        provider.configure(Map.of("credentials-path", key.toString(),
                "endpoint", stub.baseUri().toString(), "token-endpoint", stub.tokenUri().toString()));
        provider.init();

        assertThat(provider.send(request(FcmTestSupport.token(TOKEN)), null).success()).isTrue();
        assertThat(provider.getHealthDetails()).containsEntry("credentials", "service-account-file:" + key);
    }

    static Stream<Arguments> misconfigurations() {
        return Stream.of(
                Arguments.of(Map.of(), "'credentials' is required"),
                Arguments.of(Map.of("credentials", "adc"),
                        "needs the module com.github.ifrugal:push-provider-fcm-google-auth on the classpath"),
                Arguments.of(Map.of("credentials", "external-account:/etc/wif.json"),
                        "'external-account:/etc/wif.json' needs the module com.github.ifrugal:push-provider-fcm-google-auth"),
                Arguments.of(Map.of("credentials", "/a.json", "credentials-path", "/b.json"),
                        "set either 'credentials' or the deprecated 'credentials-path', not both"),
                Arguments.of(Map.of("credentials", "/a.json", "endpoint", "http://fcm.example.com"),
                        "plain http is accepted only for a loopback address"),
                Arguments.of(Map.of("credentials", "/a.json", "token-endpoint", "fcm.googleapis.com/token"),
                        "'token-endpoint' must be an absolute https URI"),
                Arguments.of(Map.of("credentials", "/a.json", "timeout", "ten"),
                        "'timeout' must be a duration such as 10s"),
                Arguments.of(Map.of("credentials", "/a.json", "timeout", "0s"), "'timeout' must be positive"),
                Arguments.of(Map.of("credentials", "/a.json", "concurrency", 0), "'concurrency' must be at least 1"),
                Arguments.of(Map.of("credentials", "/a.json", "max-tokens", "lots"), "'max-tokens' must be a whole"),
                Arguments.of(Map.of("credentials", "/a.json", "multi-token-policy", "most"),
                        "'multi-token-policy' must be one of [all, any]"),
                Arguments.of(Map.of("credentials", "/a.json", "timeout-classification", "maybe"),
                        "'timeout-classification' must be one of [ambiguous, transient]"),
                Arguments.of(Map.of("credentials", "/a.json", "token-refresh-margin", "2h"),
                        "'token-refresh-margin' must be between 0 and PT30M"),
                Arguments.of(Map.of("credentials", "/a.json", "validate-only", "maybe"),
                        "'validate-only' must be true or false"),
                Arguments.of(Map.of("credentials", "/a.json", "project-id", "bad/project"),
                        "'project-id' is not a valid Firebase project id"),
                Arguments.of(Map.of("credentials", "/a.json", "android.ttl", "1h"), "'android.ttl' must be seconds"),
                Arguments.of(Map.of("credentials", "/a.json", "android", Map.of("priority", "urgent")),
                        "'android.priority' must be normal or high"),
                Arguments.of(Map.of("credentials", "/a.json", "apns", "high"), "'apns' must be a map"));
    }

    @ParameterizedTest
    @MethodSource("misconfigurations")
    void misconfiguration_failsInConfigure_withAnActionableMessage(Map<String, Object> props, String message) {
        FcmPushProvider provider = new FcmPushProvider();

        assertThatThrownBy(() -> provider.configure(props))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageStartingWith("Failed to configure provider 'fcm' for channel 'PUSH': ")
                .hasMessageContaining(message);
    }

    @Test
    void missingProjectId_whenTheKeyNamesNone_failsInConfigure() {
        String keyWithoutProject = stub.serviceAccountJson().replace("\"project_id\":\"stub-project\",", "");

        assertThatThrownBy(() -> new FcmPushProvider().configure(Map.of("credentials", keyWithoutProject)))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("'project-id' is required");
    }

    @Test
    void httpTransportByName_needsTheSpringBean() {
        assertThatThrownBy(() -> new FcmPushProvider().configure(props("http-transport", "proxyTransport")))
                .hasMessageContaining("needs the Spring bean 'fcmPushProvider'");

        FcmHttpTransport proxy = request -> JdkFcmHttpTransport.shared().execute(request);
        FcmPushProvider provider = new FcmPushProvider(null, Map.of("proxyTransport", proxy), List.of(),
                getClass().getClassLoader());
        assertThatThrownBy(() -> provider.configure(props("http-transport", "other")))
                .hasMessageContaining("no FcmHttpTransport bean named 'other'; available: [proxyTransport]");
        provider.configure(props("http-transport", "proxyTransport"));
        assertThat(provider.transport()).isSameAs(proxy);
    }

    @Test
    void lifecycleOrder_isEnforced() {
        FcmPushProvider provider = new FcmPushProvider();

        assertThatThrownBy(provider::init).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configure(...) must be called before init()");
        provider.configure(stub.properties());
        assertThatThrownBy(() -> provider.send(request(FcmTestSupport.token(TOKEN)), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not initialized");
    }

    @Test
    void customFactory_isAskedFirst_andDestroyOrReconfigureClosesItsTokenProvider() {
        RecordingFactory factory = new RecordingFactory();
        FcmPushProvider provider = new FcmPushProvider(null, Map.of(), List.of(factory), getClass().getClassLoader());

        provider.configure(Map.of("credentials", "vault:fcm/acme", "project-id", "p",
                "endpoint", stub.baseUri().toString()));
        provider.init();
        assertThat(provider.tokenProvider()).isSameAs(factory.created.getFirst());

        provider.configure(Map.of("credentials", "vault:fcm/acme", "project-id", "p",
                "endpoint", stub.baseUri().toString()));
        assertThat(factory.created.getFirst().closed).isTrue();

        provider.init();
        provider.destroy();
        assertThat(factory.created.get(1).closed).isTrue();
        assertThat(provider.isHealthy()).isFalse();
    }

    @Test
    void healthDetails_carryNoSecrets() {
        FcmPushProvider provider = configured(stub, Map.of());

        Map<String, Object> details = provider.getHealthDetails();

        assertThat(details).containsEntry("status", "UP")
                .containsEntry("projectId", FcmStubServer.PROJECT_ID)
                .containsEntry("endpoint", stub.baseUri().toString())
                .containsEntry("credentials", "service-account-json (inline)")
                .containsEntry("transport", "JdkFcmHttpTransport")
                .containsEntry("concurrency", 8)
                .containsEntry("multiTokenPolicy", "all");
        assertThat(details.toString()).doesNotContain("PRIVATE KEY", stub.privateKeyBase64().substring(0, 20));
        assertThat(new FcmPushProvider().getHealthDetails()).containsExactly(Map.entry("status", "DOWN"));
    }

    @Test
    void withTransport_isReady_andIgnoresConfigureAndInit() {
        List<FcmHttpRequest> seen = new CopyOnWriteArrayList<>();
        FcmHttpTransport transport = request -> {
            seen.add(request);
            return new FcmHttpResponse(200, Map.of(), "{\"name\":\"projects/p/messages/seam-1\"}".getBytes());
        };
        FcmPushProvider provider = FcmPushProvider.withTransport(FcmSettings.fromMap(Map.of("project-id", "p")),
                transport, FcmErrorClassificationTest.staticToken());

        provider.configure(Map.of("credentials", "adc"));
        provider.init();
        SendResult result = provider.send(request(FcmTestSupport.token(TOKEN)), null);

        assertThat(result.messageId()).isEqualTo("projects/p/messages/seam-1");
        assertThat(seen).singleElement().satisfies(r -> {
            assertThat(r.uri()).hasToString("https://fcm.googleapis.com/v1/projects/p/messages:send");
            assertThat(r.header("authorization")).isEqualTo("Bearer static-test-token");
            assertThat(r.toString()).doesNotContain("static-test-token");
        });
    }

    @Test
    void nonPushRecipient_failsPermanently() {
        NotificationRequest email = NotificationRequest.builder().notificationType("X").channel(Channel.PUSH)
                .recipient(new EmailRecipient(null, "a@example.com", null, null, null, null)).build();

        SendResult result = configured(stub, Map.of()).send(email, null);

        assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(result.errorCode()).isEqualTo("FCM_INVALID_TARGET_SPEC");
    }

    @Test
    void publisher_defaultsToNoOp_andNullResetsIt() {
        FcmPushProvider provider = new FcmPushProvider();
        assertThat(provider.deliveryEventPublisher()).isSameAs(DeliveryEventPublisher.NO_OP);

        provider.setDeliveryEventPublisher(event -> { });
        provider.setDeliveryEventPublisher(null);

        assertThat(provider.deliveryEventPublisher()).isSameAs(DeliveryEventPublisher.NO_OP);
    }

    /** A factory for {@code vault:} specs whose token providers record {@code close()}. */
    static final class RecordingFactory implements FcmAccessTokenProviderFactory {
        final List<RecordingTokenProvider> created = new CopyOnWriteArrayList<>();

        @Override
        public boolean supports(String credentials) {
            return credentials.startsWith("vault:");
        }

        @Override
        public FcmAccessTokenProvider create(String credentials, FcmHttpTransport transport, Clock clock) {
            RecordingTokenProvider provider = new RecordingTokenProvider();
            created.add(provider);
            return provider;
        }
    }

    static final class RecordingTokenProvider implements FcmAccessTokenProvider {
        volatile boolean closed;
        final AtomicBoolean invalidated = new AtomicBoolean();

        @Override
        public FcmAccessToken token() {
            return new FcmAccessToken("vault-token", Instant.now().plusSeconds(3600));
        }

        @Override
        public void invalidate() {
            invalidated.set(true);
        }

        @Override
        public Optional<String> projectId() {
            return Optional.empty();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
