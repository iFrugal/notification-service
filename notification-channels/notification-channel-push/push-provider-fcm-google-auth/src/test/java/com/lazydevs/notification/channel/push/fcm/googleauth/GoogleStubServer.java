package com.lazydevs.notification.channel.push.fcm.googleauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback stand-in for the Google endpoints the auth library calls, plus the FCM
 * send endpoint, on {@code com.sun.net.httpserver}. No test touches the network.
 *
 * <ul>
 *   <li>{@code POST /token}: service-account JWT bearer grant; verifies the RS256
 *       signature with this server's key pair and the {@code iss}, {@code scope} and
 *       {@code aud} claims; issues {@code ya29.sa-<n>}.</li>
 *   <li>{@code POST /sts}: STS token exchange; verifies the grant type, the subject
 *       token, its type and the audience; issues {@code sts-<n>}.</li>
 *   <li>{@code POST /v1/projects/-/serviceAccounts/<email>:generateAccessToken}: IAM
 *       credentials impersonation; needs a bearer issued by {@code /sts}; issues
 *       {@code ya29.impersonated-<n>} with {@link #impersonationExpiry(Instant)}.</li>
 *   <li>{@code POST /v1/projects/<project>/messages:send}: FCM; 200 with a message name
 *       for a bearer issued by {@code /token} or {@code /v1/...:generateAccessToken}
 *       (or {@code /sts}), else 401.</li>
 * </ul>
 * Every endpoint answers a scripted response first, when one is queued for it.
 */
final class GoogleStubServer implements AutoCloseable {

    static final String SA_PROJECT = "adc-project";
    static final String SA_EMAIL = "fcm-sender@adc-project.iam.gserviceaccount.com";
    static final String SA_KEY_ID = "adc-key-id-0123456789";
    static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    static final String SUBJECT_TOKEN = "eyJ-subject-token-from-the-workload-0123456789abcdef";
    static final String SUBJECT_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:jwt";
    static final String AUDIENCE =
            "//iam.googleapis.com/projects/123456/locations/global/workloadIdentityPools/pool/providers/stub";
    static final String IMPERSONATED_EMAIL = "fcm-sender@wif-project.iam.gserviceaccount.com";

    static final String TOKEN_PATH = "/token";
    static final String STS_PATH = "/sts";
    static final String IMPERSONATION_PATH = "/v1/projects/-/serviceAccounts/" + IMPERSONATED_EMAIL
            + ":generateAccessToken";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final KeyPair keyPair;

    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<String, Queue<StubResponse>> scripts = new ConcurrentHashMap<>();
    private final List<String> problems = new CopyOnWriteArrayList<>();
    private final List<String> stsScopes = new CopyOnWriteArrayList<>();
    private final List<String> sendAuthorizations = new CopyOnWriteArrayList<>();
    private final Set<String> fcmTokens = ConcurrentHashMap.newKeySet();
    private final Set<String> stsTokens = ConcurrentHashMap.newKeySet();
    private final AtomicInteger issued = new AtomicInteger();
    private volatile long expiresInSeconds = 3600;
    private volatile Instant impersonationExpiry = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

    /** A scripted answer. */
    record StubResponse(int status, String body) {
    }

    GoogleStubServer() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.keyPair = generator.generateKeyPair();
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (NoSuchAlgorithmException | IOException e) {
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

    // ---------------------------------------------------------------- credential files

    /** A service-account key for this server's key pair, whose token_uri is {@code tokenUri}. */
    String serviceAccountJson(String tokenUri) {
        ObjectNode key = JSON.createObjectNode();
        key.put("type", "service_account");
        key.put("project_id", SA_PROJECT);
        key.put("private_key_id", SA_KEY_ID);
        key.put("private_key", privateKeyPem());
        key.put("client_email", SA_EMAIL);
        key.put("client_id", "1234567890");
        key.put("token_uri", tokenUri);
        return key.toString();
    }

    /**
     * A file-sourced workload identity federation configuration.
     *
     * @param host             the https host the token and impersonation URLs use
     * @param subjectTokenFile the file holding the subject token
     * @param impersonate      whether to add a service_account_impersonation_url
     */
    static String externalAccountJson(String host, String subjectTokenFile, boolean impersonate) {
        ObjectNode config = JSON.createObjectNode();
        config.put("type", "external_account");
        config.put("audience", AUDIENCE);
        config.put("subject_token_type", SUBJECT_TOKEN_TYPE);
        config.put("token_url", "https://" + host + STS_PATH);
        config.putObject("credential_source").put("file", subjectTokenFile);
        if (impersonate) {
            config.put("service_account_impersonation_url", "https://" + host + IMPERSONATION_PATH);
        }
        config.put("quota_project_id", "wif-project");
        return config.toString();
    }

    String privateKeyPem() {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    String privateKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    // ---------------------------------------------------------------- scripting

    void enqueue(String path, int status, String body) {
        scripts.computeIfAbsent(path, _ -> new ConcurrentLinkedQueue<>()).add(new StubResponse(status, body));
    }

    void expiresIn(long seconds) {
        expiresInSeconds = seconds;
    }

    void impersonationExpiry(Instant expiry) {
        impersonationExpiry = expiry;
    }

    // ---------------------------------------------------------------- observations

    int calls(String path) {
        AtomicInteger count = calls.get(path);
        return count == null ? 0 : count.get();
    }

    int totalCalls() {
        return calls.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    List<String> problems() {
        return List.copyOf(problems);
    }

    List<String> stsScopes() {
        return List.copyOf(stsScopes);
    }

    List<String> sendAuthorizations() {
        return List.copyOf(sendAuthorizations);
    }

    // ---------------------------------------------------------------- handlers

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String key = path.startsWith("/v1/projects/") && path.endsWith("/messages:send") ? "send" : path;
            calls.computeIfAbsent(key, _ -> new AtomicInteger()).incrementAndGet();
            byte[] raw = exchange.getRequestBody().readAllBytes();
            StubResponse scripted = scripts.getOrDefault(key, new ConcurrentLinkedQueue<>()).poll();
            if (scripted != null) {
                respond(exchange, scripted);
                return;
            }
            StubResponse answer = switch (key) {
                case TOKEN_PATH -> token(exchange, form(raw));
                case STS_PATH -> sts(form(raw));
                case IMPERSONATION_PATH -> impersonate(exchange, raw);
                case "send" -> send(exchange);
                default -> new StubResponse(404, "{\"error\":\"not_found\"}");
            };
            respond(exchange, answer);
        }
    }

    private StubResponse token(HttpExchange exchange, Map<String, String> form) {
        String problem = verifyAssertion(exchange, form);
        if (problem != null) {
            problems.add(problem);
            return new StubResponse(400, "{\"error\":\"invalid_grant\",\"error_description\":\"" + problem + "\"}");
        }
        String token = "ya29.sa-" + issued.incrementAndGet();
        fcmTokens.add(token);
        return new StubResponse(200, "{\"access_token\":\"" + token + "\",\"expires_in\":" + expiresInSeconds
                + ",\"token_type\":\"Bearer\"}");
    }

    private String verifyAssertion(HttpExchange exchange, Map<String, String> form) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("application/x-www-form-urlencoded")) {
            return "content type " + contentType;
        }
        if (!"urn:ietf:params:oauth:grant-type:jwt-bearer".equals(form.get("grant_type"))) {
            return "grant_type " + form.get("grant_type");
        }
        String[] parts = form.getOrDefault("assertion", "").split("\\.");
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
            JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (!SA_KEY_ID.equals(header.path("kid").asText())) {
                return "kid " + header.path("kid");
            }
            if (!SA_EMAIL.equals(claims.path("iss").asText())) {
                return "iss " + claims.path("iss");
            }
            if (!FCM_SCOPE.equals(claims.path("scope").asText())) {
                return "scope " + claims.path("scope");
            }
            if (!"https://oauth2.googleapis.com/token".equals(claims.path("aud").asText())) {
                return "aud " + claims.path("aud");
            }
            return null;
        } catch (Exception e) {
            return "unreadable assertion: " + e.getClass().getSimpleName();
        }
    }

    private StubResponse sts(Map<String, String> form) {
        String problem = null;
        if (!"urn:ietf:params:oauth:grant-type:token-exchange".equals(form.get("grant_type"))) {
            problem = "grant_type " + form.get("grant_type");
        } else if (!SUBJECT_TOKEN.equals(form.get("subject_token"))) {
            problem = "unexpected subject token";
        } else if (!SUBJECT_TOKEN_TYPE.equals(form.get("subject_token_type"))) {
            problem = "subject_token_type " + form.get("subject_token_type");
        } else if (!AUDIENCE.equals(form.get("audience"))) {
            problem = "audience " + form.get("audience");
        }
        if (problem != null) {
            problems.add(problem);
            return new StubResponse(400, "{\"error\":\"invalid_request\",\"error_description\":\"" + problem + "\"}");
        }
        stsScopes.add(form.get("scope"));
        String token = "sts-" + issued.incrementAndGet();
        stsTokens.add(token);
        if (FCM_SCOPE.equals(form.get("scope"))) {
            fcmTokens.add(token);
        }
        return new StubResponse(200, "{\"access_token\":\"" + token + "\",\"issued_token_type\":"
                + "\"urn:ietf:params:oauth:token-type:access_token\",\"token_type\":\"Bearer\",\"expires_in\":"
                + expiresInSeconds + "}");
    }

    private StubResponse impersonate(HttpExchange exchange, byte[] raw) throws IOException {
        String bearer = bearer(exchange);
        if (bearer == null || !stsTokens.contains(bearer)) {
            problems.add("impersonation without an STS token");
            return new StubResponse(401, "{\"error\":{\"code\":401,\"status\":\"UNAUTHENTICATED\"}}");
        }
        JsonNode body = JSON.readTree(raw);
        boolean fcmScope = false;
        for (JsonNode scope : body.path("scope")) {
            fcmScope |= FCM_SCOPE.equals(scope.asText());
        }
        if (!fcmScope) {
            problems.add("impersonation scope " + body.path("scope"));
            return new StubResponse(400, "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\"}}");
        }
        String token = "ya29.impersonated-" + issued.incrementAndGet();
        fcmTokens.add(token);
        return new StubResponse(200, "{\"accessToken\":\"" + token + "\",\"expireTime\":\""
                + impersonationExpiry + "\"}");
    }

    private StubResponse send(HttpExchange exchange) {
        String bearer = bearer(exchange);
        sendAuthorizations.add(String.valueOf(bearer));
        if (bearer == null || !fcmTokens.contains(bearer)) {
            return new StubResponse(401, "{\"error\":{\"code\":401,\"message\":\"Request had invalid authentication"
                    + " credentials.\",\"status\":\"UNAUTHENTICATED\"}}");
        }
        String project = exchange.getRequestURI().getPath().split("/")[3];
        return new StubResponse(200, "{\"name\":\"projects/" + project + "/messages/" + issued.incrementAndGet() + "\"}");
    }

    private static String bearer(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        return authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length())
                : null;
    }

    private static void respond(HttpExchange exchange, StubResponse response) throws IOException {
        byte[] bytes = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static Map<String, String> form(byte[] raw) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : new String(raw, StandardCharsets.UTF_8).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }
}
