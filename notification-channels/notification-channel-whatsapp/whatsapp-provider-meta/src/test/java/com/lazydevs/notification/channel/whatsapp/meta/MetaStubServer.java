package com.lazydevs.notification.channel.whatsapp.meta;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback stand-in for the Graph API's {@code POST /{version}/{phone-number-id}/messages}
 * on {@code com.sun.net.httpserver}. No test touches the network.
 *
 * <p>Answers 401 with Graph code 190 to a bearer token other than {@link #ACCESS_TOKEN},
 * otherwise the next scripted response, by default 200 with a new {@code wamid}.
 * Requests are recorded.
 */
final class MetaStubServer implements AutoCloseable {

    static final String ACCESS_TOKEN = "stub-access-token";
    static final String PHONE_NUMBER_ID = "100000000000001";

    private final HttpServer server;
    private final List<SendRequest> requests = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<StubResponse> script = new ConcurrentLinkedQueue<>();
    private final AtomicInteger counter = new AtomicInteger();

    /** A scripted answer; {@code delay} is slept before answering. */
    record StubResponse(int status, Map<String, String> headers, String body, Duration delay) {

        static StubResponse of(int status, String body) {
            return new StubResponse(status, Map.of(), body, Duration.ZERO);
        }

        StubResponse withHeader(String name, String value) {
            Map<String, String> copy = new LinkedHashMap<>(headers);
            copy.put(name, value);
            return new StubResponse(status, copy, body, delay);
        }

        StubResponse after(Duration wait) {
            return new StubResponse(status, headers, body, wait);
        }
    }

    /** One recorded call. */
    record SendRequest(String path, String authorization, String contentType, ObjectNode body) {
    }

    MetaStubServer() {
        try {
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Provider properties that point a tenant at this server with static credentials. */
    Map<String, Object> properties() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(MetaSettings.BASE_URL, baseUri().toString());
        props.put(MetaSettings.ACCESS_TOKEN, ACCESS_TOKEN);
        props.put(MetaSettings.PHONE_NUMBER_ID, PHONE_NUMBER_ID);
        return props;
    }

    void enqueue(StubResponse... responses) {
        script.addAll(List.of(responses));
    }

    List<SendRequest> requests() {
        return List.copyOf(requests);
    }

    // ---------------------------------------------------------------- canned bodies

    static String accepted(String wamid) {
        return "{\"messaging_product\":\"whatsapp\",\"contacts\":[{\"input\":\"15550100001\",\"wa_id\":\"15550100001\"}],"
                + "\"messages\":[{\"id\":\"" + wamid + "\"}]}";
    }

    static String graphError(int code, String message) {
        return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"OAuthException\",\"code\":" + code
                + ",\"error_data\":{\"messaging_product\":\"whatsapp\",\"details\":\"" + message + "\"},"
                + "\"fbtrace_id\":\"AbCdEf\"}}";
    }

    // ---------------------------------------------------------------- handler

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            ObjectNode body = MetaJson.readObject(raw).orElse(MetaJson.MAPPER.createObjectNode());
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            requests.add(new SendRequest(exchange.getRequestURI().getPath(), authorization,
                    exchange.getRequestHeaders().getFirst("Content-Type"), body));
            if (!("Bearer " + ACCESS_TOKEN).equals(authorization)) {
                respond(exchange, StubResponse.of(401, graphError(190, "Invalid OAuth access token.")));
                return;
            }
            StubResponse scripted = script.poll();
            respond(exchange, scripted != null ? scripted
                    : StubResponse.of(200, accepted("wamid.STUB" + counter.incrementAndGet())));
        }
    }

    private static void respond(HttpExchange exchange, StubResponse response) throws IOException {
        if (!response.delay().isZero()) {
            try {
                Thread.sleep(response.delay());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        byte[] bytes = response.body() == null ? new byte[0]
                : response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
