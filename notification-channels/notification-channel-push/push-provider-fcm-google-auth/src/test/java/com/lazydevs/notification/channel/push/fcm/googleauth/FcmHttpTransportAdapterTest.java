package com.lazydevs.notification.channel.push.fcm.googleauth;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.UrlEncodedContent;
import com.lazydevs.notification.channel.push.fcm.FcmHttpRequest;
import com.lazydevs.notification.channel.push.fcm.FcmHttpResponse;
import com.lazydevs.notification.channel.push.fcm.FcmTransportException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The google-http-client view of an {@code FcmHttpTransport}. */
class FcmHttpTransportAdapterTest {

    private static final Duration MAX = Duration.ofSeconds(5);

    private final AtomicReference<FcmHttpRequest> sent = new AtomicReference<>();

    private FcmHttpTransportAdapter adapter(FcmHttpResponse response) {
        return new FcmHttpTransportAdapter(request -> {
            sent.set(request);
            return response;
        }, MAX);
    }

    private static FcmHttpResponse json(int status, String body, Map<String, List<String>> headers) {
        Map<String, List<String>> all = new LinkedHashMap<>(headers);
        all.put("Content-Type", List.of("application/json; charset=UTF-8"));
        return new FcmHttpResponse(status, all, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void post_passesMethodUriHeadersBodyAndContentType_andReturnsTheResponse() throws IOException {
        FcmHttpTransportAdapter adapter = adapter(json(200, "{\"ok\":true}", Map.of("X-Answer", List.of("42"))));
        HttpRequest request = adapter.createRequestFactory().buildPostRequest(
                new GenericUrl("https://oauth2.example.invalid/token?x=1"),
                new UrlEncodedContent(Map.of("grant_type", "a b")));
        request.getHeaders().set("X-Custom", "v");

        HttpResponse response = request.execute();

        FcmHttpRequest captured = sent.get();
        assertThat(captured.method()).isEqualTo("POST");
        assertThat(captured.uri()).hasToString("https://oauth2.example.invalid/token?x=1");
        assertThat(captured.header("Content-Type")).isEqualTo("application/x-www-form-urlencoded; charset=UTF-8");
        assertThat(captured.header("X-Custom")).isEqualTo("v");
        assertThat(captured.header("Content-Length")).as("framing is the transport's job").isNull();
        assertThat(new String(captured.body(), StandardCharsets.UTF_8)).isEqualTo("grant_type=a+b");
        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/json; charset=UTF-8");
        assertThat(response.getHeaders().getFirstHeaderStringValue("X-Answer")).isEqualTo("42");
        assertThat(response.parseAsString()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void getWithoutContent_sendsAnEmptyBody() throws IOException {
        FcmHttpTransportAdapter adapter = adapter(json(200, "x", Map.of()));

        adapter.createRequestFactory().buildGetRequest(new GenericUrl("http://169.254.169.254/")).execute();

        assertThat(sent.get().method()).isEqualTo("GET");
        assertThat(sent.get().body()).isEmpty();
    }

    @Test
    void errorStatus_becomesTheLibrarysHttpResponseException() {
        FcmHttpTransportAdapter adapter = adapter(json(400, "{\"error\":\"invalid_grant\"}", Map.of()));

        assertThatThrownBy(() -> adapter.createRequestFactory()
                .buildPostRequest(new GenericUrl("https://sts.example.invalid/"), new UrlEncodedContent(Map.of()))
                .execute())
                .isInstanceOfSatisfying(HttpResponseException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(400);
                    assertThat(e.getContent()).contains("invalid_grant");
                });
    }

    @Test
    void gzipBody_isDecompressedByTheLibrary() throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write("{\"access_token\":\"t\"}".getBytes(StandardCharsets.UTF_8));
        }
        FcmHttpTransportAdapter adapter = adapter(new FcmHttpResponse(200,
                Map.of("Content-Encoding", List.of("gzip"), "Content-Type", List.of("application/json")),
                compressed.toByteArray()));

        HttpResponse response = adapter.createRequestFactory()
                .buildGetRequest(new GenericUrl("https://example.invalid/")).execute();

        assertThat(response.parseAsString()).isEqualTo("{\"access_token\":\"t\"}");
        assertThat(sent.get().header("Accept-Encoding")).isEqualTo("gzip");
    }

    @Test
    void noResponse_propagatesTheTransportExceptionWithItsSentFlag() {
        FcmHttpTransportAdapter adapter = new FcmHttpTransportAdapter(request -> {
            throw FcmTransportException.timedOut("no answer from " + request.uri(), null);
        }, MAX);

        assertThatThrownBy(() -> adapter.createRequestFactory()
                .buildGetRequest(new GenericUrl("https://example.invalid/")).setNumberOfRetries(0).execute())
                .isInstanceOfSatisfying(FcmTransportException.class, e -> assertThat(e.requestSent()).isTrue());
    }

    @Test
    void timeout_isConnectPlusReadCappedAtTheMaximum() throws IOException {
        FcmHttpTransportAdapter adapter = adapter(json(200, "x", Map.of()));
        GenericUrl url = new GenericUrl("https://example.invalid/");

        adapter.createRequestFactory().buildGetRequest(url).execute();
        assertThat(sent.get().timeout()).as("library default 20s + 20s").isEqualTo(MAX);

        adapter.createRequestFactory().buildGetRequest(url).setConnectTimeout(300).setReadTimeout(200).execute();
        assertThat(sent.get().timeout()).isEqualTo(Duration.ofMillis(500));

        adapter.createRequestFactory().buildGetRequest(url).setConnectTimeout(0).execute();
        assertThat(sent.get().timeout()).as("0 means no limit: the maximum").isEqualTo(MAX);
    }

    @Test
    void lowLevelRequest_dropsFramingHeadersAndJoinsRepeatedOnes() throws IOException {
        FcmHttpTransportAdapter adapter = adapter(json(204, "", Map.of()));
        LowLevelHttpRequest request = adapter.buildRequest("PUT", "https://example.invalid/latest/api/token");
        request.addHeader("Content-Length", "12");
        request.addHeader("Host", "example.invalid");
        request.addHeader("Connection", "close");
        request.addHeader("X-Aws-Ec2-Metadata-Token-Ttl-Seconds", "300");
        request.addHeader("Accept", "a");
        request.addHeader("accept", "b");

        var response = request.execute();

        assertThat(sent.get().method()).isEqualTo("PUT");
        assertThat(sent.get().headers()).containsOnlyKeys("X-Aws-Ec2-Metadata-Token-Ttl-Seconds", "Accept");
        assertThat(sent.get().header("Accept")).isEqualTo("a, b");
        assertThat(response.getStatusCode()).isEqualTo(204);
        assertThat(response.getContent()).as("no body").isNull();
        assertThat(response.getContentLength()).isZero();
    }

    @Test
    void invalidUrl_isAnIOException() {
        FcmHttpTransportAdapter adapter = adapter(json(200, "x", Map.of()));

        assertThatThrownBy(() -> adapter.buildRequest("GET", "https://exa mple.invalid/"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a valid URI");
    }

    @Test
    void nonPositiveMaximum_isRejected() {
        assertThatThrownBy(() -> new FcmHttpTransportAdapter(request -> null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
