package com.lazydevs.notification.channel.push.fcm;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.FailureTypes;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Access tokens from a Google service-account key, with no Google library:
 * a JWT signed with the key (RS256) is exchanged for an access token with the
 * OAuth 2.0 JWT bearer grant (RFC 7523) at Google's token endpoint.
 *
 * <ul>
 *   <li>JWT header: {@code alg=RS256}, {@code typ=JWT}, {@code kid=private_key_id}.</li>
 *   <li>JWT claims: {@code iss=client_email}, {@code scope=}{@value #SCOPE},
 *       {@code aud=}{@value #GOOGLE_TOKEN_URI_VALUE}, {@code iat=now}, {@code exp=now+3600}.</li>
 *   <li>The token is cached until {@code token-refresh-margin} before it expires.
 *       Concurrent callers that find it stale wait for one refresh (single flight).</li>
 *   <li>The JSON's {@code token_uri} is ignored unless it is one of Google's own:
 *       a modified key file must not be able to send the signed assertion elsewhere.
 *       Set {@code token-endpoint} to use another endpoint (tests, a private gateway).</li>
 *   <li>Failures: {@code invalid_grant}, {@code invalid_client} and every other OAuth
 *       error in a 4xx answer are {@link FailureType#PERMANENT}; 408, 429, 5xx and I/O
 *       errors are {@link FailureType#TRANSIENT}; anything else is {@link FailureType#UNKNOWN}.</li>
 * </ul>
 *
 * <p>Neither the private key, the assertion nor the access token is ever logged.
 *
 * @since 1.2.0
 */
@Slf4j
public final class ServiceAccountJwtTokenProvider implements FcmAccessTokenProvider {

    /** OAuth scope of the FCM HTTP v1 API. */
    public static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    static final String GOOGLE_TOKEN_URI_VALUE = "https://oauth2.googleapis.com/token";

    /** Google's token endpoint, also the JWT audience. */
    public static final URI GOOGLE_TOKEN_URI = URI.create(GOOGLE_TOKEN_URI_VALUE);

    /** {@code grant_type} of the JWT bearer grant. */
    public static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";

    /** Lifetime requested for the assertion; Google's maximum. */
    static final Duration ASSERTION_LIFETIME = Duration.ofHours(1);

    /** {@code token_uri} values Google has issued in service-account keys. */
    private static final Set<String> GOOGLE_TOKEN_URIS = Set.of(
            GOOGLE_TOKEN_URI_VALUE,
            "https://www.googleapis.com/oauth2/v4/token",
            "https://accounts.google.com/o/oauth2/token");

    private static final int MAX_ERROR_DESCRIPTION = 200;

    private final String clientEmail;
    private final String privateKeyId;
    private final String projectId;
    private final PrivateKey privateKey;
    private final URI tokenUri;
    private final FcmHttpTransport transport;
    private final Clock clock;
    private final Duration refreshMargin;
    private final Duration requestTimeout;

    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile Cached cached;

    private record Cached(FcmAccessToken token, Instant refreshAt) {
    }

    private ServiceAccountJwtTokenProvider(String clientEmail, String privateKeyId, String projectId,
                                           PrivateKey privateKey, URI tokenUri, FcmHttpTransport transport,
                                           Clock clock, Duration refreshMargin, Duration requestTimeout) {
        this.clientEmail = clientEmail;
        this.privateKeyId = privateKeyId;
        this.projectId = projectId;
        this.privateKey = privateKey;
        this.tokenUri = tokenUri;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refreshMargin = Objects.requireNonNull(refreshMargin, "refreshMargin");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    /**
     * Build a provider from a {@code credentials} value: inline service-account JSON
     * (starts with <code>{</code>) or the path of a service-account JSON file.
     * Reads and validates the key; makes no network call.
     *
     * @param credentials   inline JSON or a file path ({@code file:} prefix optional)
     * @param transport     the transport for the token call
     * @param clock         the clock for {@code iat}, {@code exp} and the cache
     * @param tokenEndpoint token endpoint override, {@code null} for Google's
     * @param refreshMargin refresh this long before expiry
     * @param timeout       timeout of the token call
     * @return the provider
     * @throws com.lazydevs.notification.api.exception.ProviderConfigurationException
     *         when the JSON or the key is unusable; the message never contains key material
     */
    public static ServiceAccountJwtTokenProvider fromCredentials(String credentials, FcmHttpTransport transport,
                                                                 Clock clock, URI tokenEndpoint,
                                                                 Duration refreshMargin, Duration timeout) {
        if (!FcmSettings.hasText(credentials)) {
            throw FcmSettings.invalid("'" + FcmSettings.CREDENTIALS + "' is empty");
        }
        String json;
        String source;
        if (FcmSettings.isInlineJson(credentials)) {
            json = credentials;
            source = "inline service-account JSON";
        } else {
            String location = credentials.regionMatches(true, 0, "file:", 0, 5)
                    ? credentials.substring(5)
                    : credentials;
            source = "service-account file '" + location + "'";
            try {
                json = Files.readString(Path.of(location), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                throw FcmSettings.invalid("cannot read the " + source + " (" + e.getClass().getSimpleName()
                        + "); set '" + FcmSettings.CREDENTIALS + "' to the path of a service-account JSON key,"
                        + " inline JSON, '" + FcmSettings.CREDENTIALS_ADC + "' or '"
                        + FcmSettings.CREDENTIALS_EXTERNAL_ACCOUNT + "<path>'");
            }
        }
        return fromJson(json, source, transport, clock, tokenEndpoint, refreshMargin, timeout);
    }

    private static ServiceAccountJwtTokenProvider fromJson(String json, String source, FcmHttpTransport transport,
                                                           Clock clock, URI tokenEndpoint, Duration refreshMargin,
                                                           Duration timeout) {
        JsonNode root;
        try {
            root = FcmJson.MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            // Jackson quotes the input in its message; only the position is safe to show.
            JsonLocation at = e.getLocation();
            throw FcmSettings.invalid("the " + source + " is not valid JSON"
                    + (at == null ? "" : " (line " + at.getLineNr() + ", column " + at.getColumnNr() + ")"));
        }
        if (!(root instanceof ObjectNode key)) {
            throw FcmSettings.invalid("the " + source + " is not a JSON object");
        }
        String type = FcmJson.text(key, "type");
        if (type != null && !"service_account".equals(type)) {
            throw FcmSettings.invalid("the " + source + " has type '" + type + "'; only 'service_account' keys are"
                    + " supported here. For workload identity federation use '"
                    + FcmSettings.CREDENTIALS_EXTERNAL_ACCOUNT + "<path>', for Application Default Credentials '"
                    + FcmSettings.CREDENTIALS_ADC + "'; both need " + FcmSettings.GOOGLE_AUTH_ARTIFACT);
        }
        String clientEmail = FcmJson.text(key, "client_email");
        String privateKeyPem = FcmJson.text(key, "private_key");
        if (clientEmail == null || privateKeyPem == null) {
            throw FcmSettings.invalid("the " + source + " needs 'client_email' and 'private_key'");
        }
        PrivateKey privateKey = parsePrivateKey(privateKeyPem, source);

        String declaredTokenUri = FcmJson.text(key, "token_uri");
        URI tokenUri = tokenEndpoint != null ? tokenEndpoint : GOOGLE_TOKEN_URI;
        if (tokenEndpoint == null && declaredTokenUri != null && !GOOGLE_TOKEN_URIS.contains(declaredTokenUri)) {
            log.warn("FCM service account {}: ignoring token_uri {} from the key, which is not a Google token"
                    + " endpoint; using {}. Set '{}' to use another endpoint.",
                    clientEmail, declaredTokenUri, GOOGLE_TOKEN_URI, FcmSettings.TOKEN_ENDPOINT);
        }
        return new ServiceAccountJwtTokenProvider(clientEmail, FcmJson.text(key, "private_key_id"),
                FcmJson.text(key, "project_id"), privateKey, tokenUri, transport, clock, refreshMargin, timeout);
    }

    private static PrivateKey parsePrivateKey(String pem, String source) {
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw FcmSettings.invalid("the private_key of the " + source + " is PKCS#1; a service-account key is"
                    + " PKCS#8 ('-----BEGIN PRIVATE KEY-----')");
        }
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            // The exception text could echo key bytes; only its type is shown.
            throw FcmSettings.invalid("the private_key of the " + source + " is not a PKCS#8 RSA key ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    @Override
    public FcmAccessToken token() {
        Cached current = cached;
        if (current != null && clock.instant().isBefore(current.refreshAt())) {
            return current.token();
        }
        refreshLock.lock();
        try {
            // Another caller may have refreshed while this one waited.
            current = cached;
            if (current != null && clock.instant().isBefore(current.refreshAt())) {
                return current.token();
            }
            Cached fresh = fetch();
            cached = fresh;
            return fresh.token();
        } finally {
            refreshLock.unlock();
        }
    }

    @Override
    public void invalidate() {
        cached = null;
    }

    @Override
    public Optional<String> projectId() {
        return Optional.ofNullable(projectId);
    }

    @Override
    public void close() {
        cached = null;
    }

    /** @return the endpoint the assertion is posted to */
    public URI tokenUri() {
        return tokenUri;
    }

    /** @return the service account's email (not a secret) */
    public String clientEmail() {
        return clientEmail;
    }

    private Cached fetch() {
        Instant now = clock.instant();
        String form = "grant_type=" + URLEncoder.encode(GRANT_TYPE, StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion(now), StandardCharsets.UTF_8);
        FcmHttpRequest request = new FcmHttpRequest("POST", tokenUri,
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"),
                form.getBytes(StandardCharsets.UTF_8), requestTimeout);
        FcmHttpResponse response;
        try {
            response = transport.execute(request);
        } catch (FcmTransportException e) {
            throw new FcmAuthenticationException("FCM token endpoint " + tokenUri + " did not answer: "
                    + e.getMessage(), FailureType.TRANSIENT, e);
        } catch (RuntimeException e) {
            throw new FcmAuthenticationException("FCM token endpoint " + tokenUri + " call failed: "
                    + e.getClass().getSimpleName(), FailureType.UNKNOWN, e);
        }
        Optional<ObjectNode> body = FcmJson.readObject(response.body());
        if (response.isSuccess()) {
            String accessToken = body.map(b -> FcmJson.text(b, "access_token")).orElse(null);
            if (accessToken == null) {
                throw new FcmAuthenticationException("FCM token endpoint " + tokenUri
                        + " answered " + response.statusCode() + " without an access_token", FailureType.UNKNOWN, null);
            }
            JsonNode expiresIn = body.get().get("expires_in");
            long lifetimeSeconds = expiresIn != null && expiresIn.canConvertToLong() && expiresIn.asLong() > 0
                    ? expiresIn.asLong()
                    : ASSERTION_LIFETIME.toSeconds();
            Duration lifetime = Duration.ofSeconds(lifetimeSeconds);
            Instant expiresAt = now.plus(lifetime);
            // A margin longer than half the lifetime would refresh on nearly every call.
            Duration margin = refreshMargin.compareTo(lifetime.dividedBy(2)) > 0 ? lifetime.dividedBy(2) : refreshMargin;
            log.debug("Fetched an FCM access token for {}, expires at {}", clientEmail, expiresAt);
            return new Cached(new FcmAccessToken(accessToken, expiresAt), expiresAt.minus(margin));
        }
        String error = body.map(b -> FcmJson.text(b, "error")).orElse(null);
        String description = body.map(b -> FcmJson.text(b, "error_description")).orElse(null);
        int status = response.statusCode();
        FailureType type;
        if (FailureTypes.fromHttpStatus(status) == FailureType.TRANSIENT) {
            type = FailureType.TRANSIENT;
        } else if (status >= 400 && status < 500 && error != null) {
            type = FailureType.PERMANENT;
        } else {
            type = FailureType.UNKNOWN;
        }
        String message = "FCM token endpoint " + tokenUri + " rejected the service account " + clientEmail
                + ": HTTP " + status + (error == null ? "" : " " + error)
                + (description == null ? "" : " (" + truncate(description) + ")");
        if (type == FailureType.PERMANENT) {
            message += "; check that the key is not deleted or disabled and that the clock is correct";
        }
        throw new FcmAuthenticationException(message, type, null);
    }

    /** The signed JWT assertion for {@code now}. */
    String assertion(Instant now) {
        ObjectNode header = FcmJson.MAPPER.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        if (privateKeyId != null) {
            header.put("kid", privateKeyId);
        }
        long iat = now.getEpochSecond();
        ObjectNode claims = FcmJson.MAPPER.createObjectNode();
        claims.put("iss", clientEmail);
        claims.put("scope", SCOPE);
        claims.put("aud", GOOGLE_TOKEN_URI_VALUE);
        claims.put("iat", iat);
        claims.put("exp", iat + ASSERTION_LIFETIME.toSeconds());
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String signingInput = encoder.encodeToString(FcmJson.write(header)) + "."
                + encoder.encodeToString(FcmJson.write(claims));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + encoder.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new FcmAuthenticationException("cannot sign the FCM token assertion ("
                    + e.getClass().getSimpleName() + ")", FailureType.PERMANENT, null);
        }
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ERROR_DESCRIPTION ? text : text.substring(0, MAX_ERROR_DESCRIPTION) + "...";
    }

    @Override
    public String toString() {
        return "ServiceAccountJwtTokenProvider[clientEmail=" + clientEmail + ", tokenUri=" + tokenUri + "]";
    }
}
