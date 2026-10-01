package com.lazydevs.notification.channel.email.acs;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.channel.email.acs.AcsEmailProperties.SendMode;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * The derived operation id reaches the wire as the {@code Operation-Id} header
 * of the ACS send request, through the production client configuration
 * ({@link AcsEmailClientFactory}) and the real azure-core pipeline, with only the
 * HTTP transport replaced.
 */
class AcsOperationIdHeaderTest {

    private static final String ENDPOINT = "https://x.communication.azure.com";
    private static final HttpHeaderName OPERATION_ID = HttpHeaderName.fromString("Operation-Id");

    /** What the transport saw, read at send time (the pipeline may reuse request objects). */
    private record Sent(HttpMethod method, String url, String operationId) {
    }

    /** Scripted transport: records each request and answers with the next scripted response. */
    private static final class ScriptedHttpClient implements HttpClient {
        final List<Sent> sent = new CopyOnWriteArrayList<>();
        final Deque<Integer> postStatuses = new ArrayDeque<>();
        String finalStatus = "Succeeded";

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            String id = request.getHeaders().getValue(OPERATION_ID);
            sent.add(new Sent(request.getHttpMethod(), request.getUrl().toString(), id));
            String opId = id != null ? id : "acs-chosen-id";
            if (request.getHttpMethod() == HttpMethod.POST) {
                int status = postStatuses.isEmpty() ? 202 : postStatuses.pop();
                if (status != 202) {
                    return Mono.just(new JsonResponse(request, status, new HttpHeaders().set("retry-after-ms", "1"),
                            "{\"error\":{\"code\":\"ServiceUnavailable\",\"message\":\"try again\"}}"));
                }
                return Mono.just(new JsonResponse(request, 202, new HttpHeaders()
                        .set("Operation-Location", ENDPOINT + "/emails/operations/" + opId + "?api-version=2023-03-31")
                        .set("retry-after-ms", "1"),
                        "{\"id\":\"" + opId + "\",\"status\":\"Running\"}"));
            }
            return Mono.just(new JsonResponse(request, 200, new HttpHeaders().set("retry-after-ms", "1"),
                    "{\"id\":\"" + opId + "\",\"status\":\"" + finalStatus + "\"}"));
        }

        List<Sent> posts() {
            return sent.stream().filter(s -> s.method() == HttpMethod.POST).toList();
        }

        List<Sent> gets() {
            return sent.stream().filter(s -> s.method() == HttpMethod.GET).toList();
        }
    }

    private static final class JsonResponse extends HttpResponse {
        private final int status;
        private final HttpHeaders headers;
        private final byte[] body;

        JsonResponse(HttpRequest request, int status, HttpHeaders headers, String body) {
            super(request);
            this.status = status;
            this.headers = headers.set("Content-Type", "application/json");
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int getStatusCode() {
            return status;
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getHeaderValue(String name) {
            return headers.getValue(name);
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public Flux<ByteBuffer> getBody() {
            return Flux.just(ByteBuffer.wrap(body));
        }

        @Override
        public Mono<byte[]> getBodyAsByteArray() {
            return Mono.just(body);
        }

        @Override
        public Mono<String> getBodyAsString() {
            return Mono.just(new String(body, StandardCharsets.UTF_8));
        }

        @Override
        public Mono<String> getBodyAsString(Charset charset) {
            return Mono.just(new String(body, charset));
        }
    }

    /** A provider over the production client factory, with only the transport swapped. */
    private static AcsEmailProvider provider(ScriptedHttpClient http, SendMode mode, int sdkRetries) {
        AcsEmailProperties settings = AcsEmailProperties.fromMap(Map.of(
                "connection-string", "endpoint=" + ENDPOINT + "/;accesskey=a2V5",
                "sender", "DoNotReply@example.com",
                "send-mode", mode.name().toLowerCase(),
                "sdk-retries", String.valueOf(sdkRetries)));
        EmailClientBuilder builder = spy(new EmailClientBuilder());
        builder.httpClient(http);
        // The factory installs its shared transport; keep the scripted one instead.
        doReturn(builder).when(builder).httpClient(any());
        AcsEmailClientFactory factory = new AcsEmailClientFactory(Map.of(),
                AcsOperationIdHeaderTest.class.getClassLoader(), () -> builder);
        EmailClient client = factory.createClient(settings, factory.resolveCredential(settings));
        return new AcsEmailProvider(settings,
                new SdkAcsEmailGateway(client, settings.sendMode(), Duration.ofSeconds(10)));
    }

    private static NotificationRequest request() {
        return NotificationRequest.builder()
                .requestId("req-42")
                .tenantId("acme")
                .notificationType("WELCOME")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .build();
    }

    private static final RenderedContent CONTENT = RenderedContent.email("Welcome", "<p>Hi</p>", "Hi");

    private static UUID expectedId(AcsEmailProvider provider, NotificationRequest request) {
        return AcsEmailProvider.operationIdFor("acme", "req-42", provider.toEmailMessage(request, CONTENT));
    }

    @Test
    void submitMode_postCarriesTheDerivedOperationId_onEverySdkRetry_andNoStatusCallIsMade() {
        ScriptedHttpClient http = new ScriptedHttpClient();
        http.postStatuses.add(503);
        AcsEmailProvider provider = provider(http, SendMode.SUBMIT, 1);
        NotificationRequest request = request();

        SendResult result = provider.send(request, CONTENT);

        String expected = expectedId(provider, request).toString();
        assertThat(http.posts()).hasSize(2)
                .allSatisfy(post -> {
                    assertThat(post.url()).startsWith(ENDPOINT + "/emails:send");
                    assertThat(post.operationId()).isEqualTo(expected);
                });
        assertThat(http.gets()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo(expected);
        assertThat(result.providerMetadata()).containsEntry("acsStatus", "SUBMITTED");
    }

    @Test
    void waitMode_postCarriesTheDerivedOperationId_andTheFinalStatusIsRead() {
        ScriptedHttpClient http = new ScriptedHttpClient();
        AcsEmailProvider provider = provider(http, SendMode.WAIT, 0);
        NotificationRequest request = request();

        SendResult result = provider.send(request, CONTENT);

        String expected = expectedId(provider, request).toString();
        assertThat(http.posts()).singleElement()
                .satisfies(post -> assertThat(post.operationId()).isEqualTo(expected));
        assertThat(http.gets()).isNotEmpty()
                .allSatisfy(get -> assertThat(get.url()).contains("/emails/operations/" + expected));
        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo(expected);
        assertThat(result.providerMetadata()).containsEntry("acsStatus", "SUCCEEDED");
    }

    @Test
    void sameRequestSentTwice_reusesTheOperationId() {
        ScriptedHttpClient http = new ScriptedHttpClient();
        AcsEmailProvider provider = provider(http, SendMode.SUBMIT, 0);

        provider.send(request(), CONTENT);
        provider.send(request(), CONTENT);

        assertThat(http.posts()).hasSize(2);
        assertThat(http.posts().get(0).operationId()).isNotNull()
                .isEqualTo(http.posts().get(1).operationId());
    }

    @Test
    void legacyGatewayCall_sendsNoOperationIdHeader() {
        ScriptedHttpClient http = new ScriptedHttpClient();
        AcsEmailProperties settings = AcsEmailProperties.fromMap(Map.of(
                "connection-string", "endpoint=" + ENDPOINT + "/;accesskey=a2V5",
                "sender", "DoNotReply@example.com", "send-mode", "submit"));
        EmailClient client = new EmailClientBuilder()
                .connectionString("endpoint=" + ENDPOINT + "/;accesskey=a2V5").httpClient(http).buildClient();
        AcsEmailProvider provider = new AcsEmailProvider(settings, null);

        AcsSendOutcome outcome = new SdkAcsEmailGateway(client, SendMode.SUBMIT, Duration.ofSeconds(10))
                .send(provider.toEmailMessage(request(), CONTENT));

        assertThat(http.posts()).singleElement().satisfies(post -> assertThat(post.operationId()).isNull());
        assertThat(outcome.operationId()).isEqualTo("acs-chosen-id");
    }
}
