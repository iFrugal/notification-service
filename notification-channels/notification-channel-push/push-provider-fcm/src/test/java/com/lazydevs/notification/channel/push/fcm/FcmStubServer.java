package com.lazydevs.notification.channel.push.fcm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A loopback stand-in for Google's OAuth token endpoint and the FCM HTTP v1 send
 * endpoint, on {@code com.sun.net.httpserver}. No test touches the network.
 *
 * <ul>
 *   <li>{@code POST /token}: checks the JWT bearer grant and the assertion (RS256
 *       signature with the key pair generated for this server, {@code iss},
 *       {@code scope}, {@code aud}, {@code exp = iat + 3600}) and issues
 *       {@code stub-access-token-<n>}; or answers with a scripted response.</li>
 *   <li>{@code POST /v1/projects/{project}/messages:send}: answers 401 without an
 *       FcmError for a bearer token it did not issue (or {@link #revokeTokens() revoked}),
 *       otherwise the scripted response for the request, by default 200 with a
 *       message name. Requests, in-flight count and its maximum are recorded.</li>
 * </ul>
 */
final class FcmStubServer implements AutoCloseable {

    static final String PROJECT_ID = "stub-project";
    static final String CLIENT_EMAIL = "fcm-sender@stub-project.iam.gserviceaccount.com";
    static final String PRIVATE_KEY_ID = "stub-key-id-0123456789";

    private final HttpServer server;
    private final KeyPair keyPair;
    private final Clock clock;

    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger issued = new AtomicInteger();
    private final Set<String> validTokens = ConcurrentHashMap.newKeySet();
    private final List<String> assertionProblems = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<StubResponse> tokenScript = new ConcurrentLinkedQueue<>();
    private volatile long tokenExpiresInSeconds = 3600;
    private volatile Duration tokenDelay = Duration.ZERO;

    private final List<SendRequest> sendRequests = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<StubResponse> sendScript = new ConcurrentLinkedQueue<>();
    private volatile Function<SendRequest, StubResponse> sendResponder = request -> null;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger messageCounter = new AtomicInteger();

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

    /** One recorded send call. */
    record SendRequest(String path, String authorization, String contentType, ObjectNode body) {

        ObjectNode message() {
            return (ObjectNode) body.get("message");
        }

        /** The token, fid, topic or condition the message was addressed to. */
        String target() {
            for (String field : List.of("token", "fid", "topic", "condition")) {
                if (message().has(field)) {
                    return message().get(field).asText();
                }
            }
            return null;
        }
    }

    FcmStubServer() {
        this(Clock.systemUTC());
    }

    FcmStubServer(Clock clock) {
        this.clock = clock;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.keyPair = generator.generateKeyPair();
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/token", this::handleToken);
        server.createContext("/v1/projects/", this::handleSend);
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    URI tokenUri() {
        return URI.create(baseUri() + "/token");
    }

    /** Provider properties that point a tenant at this server with an inline service-account key. */
    Map<String, Object> properties() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(FcmSettings.CREDENTIALS, serviceAccountJson());
        props.put(FcmSettings.ENDPOINT, baseUri().toString());
        props.put(FcmSettings.TOKEN_ENDPOINT, tokenUri().toString());
        return props;
    }

    /** A service-account JSON key for this server's key pair. */
    String serviceAccountJson() {
        return serviceAccountJson("https://oauth2.googleapis.com/token");
    }

    String serviceAccountJson(String tokenUri) {
        ObjectNode key = FcmJson.MAPPER.createObjectNode();
        key.put("type", "service_account");
        key.put("project_id", PROJECT_ID);
        key.put("private_key_id", PRIVATE_KEY_ID);
        key.put("private_key", privateKeyPem());
        key.put("client_email", CLIENT_EMAIL);
        key.put("client_id", "1234567890");
        key.put("token_uri", tokenUri);
        return FcmJson.writeString(key);
    }

    String privateKeyPem() {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    /** The base64 body of the private key, to prove it never reaches a log line. */
    String privateKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    // ---------------------------------------------------------------- scripting

    void enqueueToken(StubResponse response) {
        tokenScript.add(response);
    }

    void tokenExpiresIn(long seconds) {
        tokenExpiresInSeconds = seconds;
    }

    /** Every issued token answer waits this long, to make concurrent refreshes overlap. */
    void tokenDelay(Duration delay) {
        tokenDelay = delay;
    }

    void enqueueSend(StubResponse... responses) {
        sendScript.addAll(List.of(responses));
    }

    /** Answer by request; {@code null} from the function means the default 200. */
    void respondWith(Function<SendRequest, StubResponse> responder) {
        this.sendResponder = responder;
    }

    /** Every issued access token becomes unknown, as after a key rotation. */
    void revokeTokens() {
        validTokens.clear();
    }

    // ---------------------------------------------------------------- observations

    int tokenCalls() {
        return tokenCalls.get();
    }

    List<String> assertionProblems() {
        return List.copyOf(assertionProblems);
    }

    List<SendRequest> sendRequests() {
        return List.copyOf(sendRequests);
    }

    int maxInFlight() {
        return maxInFlight.get();
    }

    // ---------------------------------------------------------------- canned bodies

    static String success(String messageName) {
        return "{\"name\":\"" + messageName + "\"}";
    }

    /** A google.rpc.Status error with an FcmError detail (null errorCode: no detail). */
    static String fcmError(int code, String status, String errorCode, String message) {
        return fcmError(code, status, errorCode, message, null);
    }

    static String fcmError(int code, String status, String errorCode, String message, String violatedField) {
        ObjectNode root = FcmJson.MAPPER.createObjectNode();
        ObjectNode error = root.putObject("error");
        error.put("code", code);
        error.put("message", message);
        error.put("status", status);
        ArrayNode details = error.putArray("details");
        if (errorCode != null) {
            details.addObject()
                    .put("@type", "type.googleapis.com/google.firebase.fcm.v1.FcmError")
                    .put("errorCode", errorCode);
        }
        if (violatedField != null) {
            ObjectNode badRequest = details.addObject();
            badRequest.put("@type", "type.googleapis.com/google.rpc.BadRequest");
            badRequest.putArray("fieldViolations").addObject()
                    .put("field", violatedField)
                    .put("description", "Invalid registration token");
        }
        return FcmJson.writeString(root);
    }

    // ---------------------------------------------------------------- handlers

    private void handleToken(HttpExchange exchange) throws IOException {
        tokenCalls.incrementAndGet();
        try (exchange) {
            Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            StubResponse scripted = tokenScript.poll();
            if (scripted != null) {
                respond(exchange, scripted);
                return;
            }
            String problem = verifyGrant(exchange, form);
            if (problem != null) {
                assertionProblems.add(problem);
                respond(exchange, StubResponse.of(400,
                        "{\"error\":\"invalid_grant\",\"error_description\":\"" + problem + "\"}"));
                return;
            }
            String token = "stub-access-token-" + issued.incrementAndGet();
            validTokens.add(token);
            respond(exchange, StubResponse.of(200, "{\"access_token\":\"" + token + "\",\"expires_in\":"
                    + tokenExpiresInSeconds + ",\"token_type\":\"Bearer\"}").after(tokenDelay));
        }
    }

    private String verifyGrant(HttpExchange exchange, Map<String, String> form) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (!"application/x-www-form-urlencoded".equals(contentType)) {
            return "content type " + contentType;
        }
        if (!ServiceAccountJwtTokenProvider.GRANT_TYPE.equals(form.get("grant_type"))) {
            return "grant_type " + form.get("grant_type");
        }
        String assertion = form.get("assertion");
        String[] parts = assertion == null ? new String[0] : assertion.split("\\.");
        if (parts.length != 3) {
            return "assertion is not a JWS";
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(keyPair.getPublic());
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                return "bad signature";
            }
            JsonNode header = FcmJson.MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode claims = FcmJson.MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (!"RS256".equals(header.path("alg").asText()) || !PRIVATE_KEY_ID.equals(header.path("kid").asText())) {
                return "header " + header;
            }
            if (!CLIENT_EMAIL.equals(claims.path("iss").asText())) {
                return "iss " + claims.path("iss");
            }
            if (!ServiceAccountJwtTokenProvider.SCOPE.equals(claims.path("scope").asText())) {
                return "scope " + claims.path("scope");
            }
            if (!"https://oauth2.googleapis.com/token".equals(claims.path("aud").asText())) {
                return "aud " + claims.path("aud");
            }
            long iat = claims.path("iat").asLong();
            long exp = claims.path("exp").asLong();
            if (exp != iat + 3600) {
                return "exp " + exp + " is not iat + 3600";
            }
            if (Math.abs(iat - clock.instant().getEpochSecond()) > 300) {
                return "iat " + iat + " is not now";
            }
            return null;
        } catch (Exception e) {
            return "unreadable assertion: " + e.getClass().getSimpleName();
        }
    }

    private void handleSend(HttpExchange exchange) throws IOException {
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try (exchange) {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            ObjectNode body = FcmJson.readObject(raw).orElse(FcmJson.MAPPER.createObjectNode());
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            SendRequest request = new SendRequest(exchange.getRequestURI().getPath(), authorization,
                    exchange.getRequestHeaders().getFirst("Content-Type"), body);
            sendRequests.add(request);

            String bearer = authorization != null && authorization.startsWith("Bearer ")
                    ? authorization.substring("Bearer ".length())
                    : null;
            if (bearer == null || !validTokens.contains(bearer)) {
                respond(exchange, StubResponse.of(401, fcmError(401, "UNAUTHENTICATED", null,
                        "Request had invalid authentication credentials.")));
                return;
            }
            StubResponse scripted = sendScript.poll();
            if (scripted == null) {
                scripted = sendResponder.apply(request);
            }
            if (scripted == null) {
                String project = exchange.getRequestURI().getPath().split("/")[3];
                scripted = StubResponse.of(200, success("projects/" + project + "/messages/"
                        + messageCounter.incrementAndGet()));
            }
            respond(exchange, scripted);
        } finally {
            inFlight.decrementAndGet();
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
        byte[] bytes = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    /** All recorded targets, in arrival order. */
    List<String> targets() {
        List<String> out = new ArrayList<>();
        sendRequests.forEach(r -> out.add(r.target()));
        return out;
    }
}
