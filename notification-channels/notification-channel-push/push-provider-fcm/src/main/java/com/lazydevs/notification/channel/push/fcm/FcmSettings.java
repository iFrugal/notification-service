package com.lazydevs.notification.channel.push.fcm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lazydevs.notification.api.exception.ProviderConfigurationException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings of one {@link FcmPushProvider} instance, parsed from the per-tenant
 * provider properties map that {@code ProviderRegistry} hands to
 * {@link FcmPushProvider#configure(Map)}.
 *
 * <p>A plain value object: every tenant configures its own provider instance.
 * Recognised keys (unknown keys are ignored, so channel-level settings merged in
 * by the registry do no harm):
 * <ul>
 *   <li>{@code project-id} - Firebase project id; defaults to {@code project_id} of a
 *       service-account JSON.</li>
 *   <li>{@code credentials} - a service-account JSON file path, inline service-account
 *       JSON (starts with <code>{</code>), {@code adc} or {@code external-account:<path>}.
 *       The last two need {@code com.github.ifrugal:push-provider-fcm-google-auth}.
 *       {@code credentials-path} is a deprecated alias.</li>
 *   <li>{@code validate-only} - send with {@code validate_only: true}: FCM validates
 *       the message and delivers nothing. Default {@code false}.</li>
 *   <li>{@code dry-run} - make no HTTP call at all and report success with a
 *       {@code dry-run:<uuid>} id. Default {@code false}.</li>
 *   <li>{@code timeout} - per HTTP request, default {@code 10s}.</li>
 *   <li>{@code timeout-classification} - {@code ambiguous} (default) or {@code transient}:
 *       how a failure after the request was sent (timeout, lost connection) is classified.</li>
 *   <li>{@code concurrency} - in-flight requests per tenant, default {@code 8}.</li>
 *   <li>{@code multi-token-policy} - {@code all} (default) or {@code any}.</li>
 *   <li>{@code max-tokens} - largest {@code deviceTokens} list accepted, default {@code 500}.</li>
 *   <li>{@code log-token-hash} - log {@code sha256:<16 hex>} of the target, default {@code true}.</li>
 *   <li>{@code token-refresh-margin} - refresh the access token this long before it
 *       expires, default {@code 5m}.</li>
 *   <li>{@code endpoint}, {@code token-endpoint} - override the FCM and OAuth endpoints
 *       (https, or http to a loopback address only).</li>
 *   <li>{@code http-transport} - name of an {@link FcmHttpTransport} bean for this tenant.</li>
 *   <li>{@code android.*}, {@code apns.*}, {@code webpush.*} - tenant defaults for the
 *       platform blocks of every message, as nested maps or dotted keys.</li>
 * </ul>
 * Durations are ISO-8601 ({@code PT10S}) or a number with a unit
 * ({@code 500ms}, {@code 10s}, {@code 5m}, {@code 1h}).
 *
 * @param projectId             Firebase project id, may be {@code null} when the credentials name it
 * @param credentials           credentials spec, may be {@code null} only for a provider built with
 *                              {@link FcmPushProvider#withTransport}
 * @param validateOnly          send with {@code validate_only}
 * @param dryRun                skip the HTTP call
 * @param timeout               per-request timeout
 * @param timeoutClassification classification of a failure after the request was sent
 * @param concurrency           in-flight requests per provider instance
 * @param multiTokenPolicy      aggregate rule for {@code deviceTokens}
 * @param maxTokens             largest accepted {@code deviceTokens} list
 * @param logTokenHash          whether logs carry the target hash
 * @param tokenRefreshMargin    access token refresh margin
 * @param endpoint              FCM base URI, without a trailing slash
 * @param tokenEndpoint         OAuth token URI override, {@code null} for Google's
 * @param httpTransport         name of an {@link FcmHttpTransport} bean, may be {@code null}
 * @param androidDefaults       tenant default {@code android} block, may be {@code null}
 * @param apnsDefaults          tenant default {@code apns} block, may be {@code null}
 * @param webpushDefaults       tenant default {@code webpush} block, may be {@code null}
 * @since 1.2.0
 */
public record FcmSettings(
        String projectId,
        String credentials,
        boolean validateOnly,
        boolean dryRun,
        Duration timeout,
        TimeoutClassification timeoutClassification,
        int concurrency,
        MultiTokenPolicy multiTokenPolicy,
        int maxTokens,
        boolean logTokenHash,
        Duration tokenRefreshMargin,
        URI endpoint,
        URI tokenEndpoint,
        String httpTransport,
        ObjectNode androidDefaults,
        ObjectNode apnsDefaults,
        ObjectNode webpushDefaults) {

    static final String PROVIDER_NAME = "fcm";
    static final String CHANNEL = "PUSH";

    public static final String PROJECT_ID = "project-id";
    public static final String CREDENTIALS = "credentials";
    /** Deprecated alias of {@link #CREDENTIALS}. */
    public static final String CREDENTIALS_PATH = "credentials-path";
    public static final String VALIDATE_ONLY = "validate-only";
    public static final String DRY_RUN = "dry-run";
    public static final String TIMEOUT = "timeout";
    public static final String TIMEOUT_CLASSIFICATION = "timeout-classification";
    public static final String CONCURRENCY = "concurrency";
    public static final String MULTI_TOKEN_POLICY = "multi-token-policy";
    public static final String MAX_TOKENS = "max-tokens";
    public static final String LOG_TOKEN_HASH = "log-token-hash";
    public static final String TOKEN_REFRESH_MARGIN = "token-refresh-margin";
    public static final String ENDPOINT = "endpoint";
    public static final String TOKEN_ENDPOINT = "token-endpoint";
    public static final String HTTP_TRANSPORT = "http-transport";

    /** Credentials value for Application Default Credentials (adapter module). */
    public static final String CREDENTIALS_ADC = "adc";
    /** Credentials prefix for a workload identity federation config file (adapter module). */
    public static final String CREDENTIALS_EXTERNAL_ACCOUNT = "external-account:";
    /** Maven coordinates of the module that supports {@code adc} and {@code external-account:}. */
    public static final String GOOGLE_AUTH_ARTIFACT = "com.github.ifrugal:push-provider-fcm-google-auth";

    public static final URI DEFAULT_ENDPOINT = URI.create("https://fcm.googleapis.com");
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    public static final int DEFAULT_CONCURRENCY = 8;
    public static final int DEFAULT_MAX_TOKENS = 500;
    public static final Duration DEFAULT_TOKEN_REFRESH_MARGIN = Duration.ofMinutes(5);

    /** Access tokens live one hour; a margin must leave part of that. */
    private static final Duration MAX_TOKEN_REFRESH_MARGIN = Duration.ofMinutes(30);
    private static final List<String> PLATFORMS = List.of("android", "apns", "webpush");
    private static final Pattern SIMPLE_DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)");
    private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");

    /**
     * How a send failure is classified when the request went out but no response came back.
     */
    public enum TimeoutClassification {
        /** {@code AMBIGUOUS}: not retried by default, so the device cannot get it twice. */
        AMBIGUOUS,
        /** {@code TRANSIENT}: retried; a duplicate notification is acceptable for this tenant. */
        TRANSIENT
    }

    /**
     * When a send to several {@code deviceTokens} counts as a success.
     */
    public enum MultiTokenPolicy {
        /** Every token must succeed; a partial result is {@code AMBIGUOUS}. */
        ALL,
        /** One successful token is enough. */
        ANY
    }

    /**
     * Copies the tenant default blocks.
     */
    public FcmSettings {
        androidDefaults = androidDefaults == null ? null : androidDefaults.deepCopy();
        apnsDefaults = apnsDefaults == null ? null : apnsDefaults.deepCopy();
        webpushDefaults = webpushDefaults == null ? null : webpushDefaults.deepCopy();
    }

    /** @return a copy of the tenant default {@code android} block, or {@code null} */
    @Override
    public ObjectNode androidDefaults() {
        return androidDefaults == null ? null : androidDefaults.deepCopy();
    }

    /** @return a copy of the tenant default {@code apns} block, or {@code null} */
    @Override
    public ObjectNode apnsDefaults() {
        return apnsDefaults == null ? null : apnsDefaults.deepCopy();
    }

    /** @return a copy of the tenant default {@code webpush} block, or {@code null} */
    @Override
    public ObjectNode webpushDefaults() {
        return webpushDefaults == null ? null : webpushDefaults.deepCopy();
    }

    /**
     * Parse and validate the provider properties map.
     *
     * @param properties merged channel and provider properties (may be {@code null})
     * @return the validated settings
     * @throws ProviderConfigurationException when a value is malformed
     */
    public static FcmSettings fromMap(Map<String, Object> properties) {
        Map<String, Object> props = properties == null ? Map.of() : properties;

        String projectId = string(props, PROJECT_ID);
        if (projectId != null && !PROJECT_ID_PATTERN.matcher(projectId).matches()) {
            throw invalid("'" + PROJECT_ID + "' is not a valid Firebase project id: '" + projectId + "'");
        }

        String credentials = string(props, CREDENTIALS);
        String credentialsPath = string(props, CREDENTIALS_PATH);
        if (credentials != null && credentialsPath != null) {
            throw invalid("set either '" + CREDENTIALS + "' or the deprecated '" + CREDENTIALS_PATH
                    + "', not both");
        }
        if (credentials == null) {
            credentials = credentialsPath;
        }

        URI tokenEndpoint = props.get(TOKEN_ENDPOINT) == null || isBlank(props.get(TOKEN_ENDPOINT))
                ? null
                : endpoint(props, TOKEN_ENDPOINT, null);

        ObjectNode platforms = platformDefaults(props);
        return new FcmSettings(
                projectId,
                credentials,
                bool(props, VALIDATE_ONLY, false),
                bool(props, DRY_RUN, false),
                positiveDuration(props, TIMEOUT, DEFAULT_TIMEOUT),
                enumValue(props, TIMEOUT_CLASSIFICATION, TimeoutClassification.class,
                        TimeoutClassification.AMBIGUOUS),
                positiveInt(props, CONCURRENCY, DEFAULT_CONCURRENCY),
                enumValue(props, MULTI_TOKEN_POLICY, MultiTokenPolicy.class, MultiTokenPolicy.ALL),
                positiveInt(props, MAX_TOKENS, DEFAULT_MAX_TOKENS),
                bool(props, LOG_TOKEN_HASH, true),
                refreshMargin(props),
                endpoint(props, ENDPOINT, DEFAULT_ENDPOINT),
                tokenEndpoint,
                string(props, HTTP_TRANSPORT),
                (ObjectNode) platforms.get("android"),
                (ObjectNode) platforms.get("apns"),
                (ObjectNode) platforms.get("webpush"));
    }

    /** @return whether the deprecated {@code credentials-path} key was used */
    static boolean usesDeprecatedCredentialsPath(Map<String, Object> properties) {
        return properties != null && string(properties, CREDENTIALS) == null
                && string(properties, CREDENTIALS_PATH) != null;
    }

    /**
     * What kind of credentials a spec names, without revealing inline JSON.
     *
     * @param credentials the spec, may be {@code null}
     * @return {@code service-account-json (inline)}, {@code adc},
     *         {@code external-account:<path>}, {@code service-account-file:<path>} or {@code null}
     */
    public static String describeCredentials(String credentials) {
        if (credentials == null) {
            return null;
        }
        if (isInlineJson(credentials)) {
            return "service-account-json (inline)";
        }
        if (CREDENTIALS_ADC.equalsIgnoreCase(credentials)) {
            return CREDENTIALS_ADC;
        }
        if (isExternalAccount(credentials)) {
            return credentials;
        }
        return "service-account-file:" + credentials;
    }

    static boolean isInlineJson(String credentials) {
        return credentials != null && credentials.stripLeading().startsWith("{");
    }

    static boolean isExternalAccount(String credentials) {
        return credentials != null
                && credentials.regionMatches(true, 0, CREDENTIALS_EXTERNAL_ACCOUNT, 0,
                CREDENTIALS_EXTERNAL_ACCOUNT.length());
    }

    /** @return whether the spec needs the google-auth adapter module */
    static boolean needsGoogleAuth(String credentials) {
        return CREDENTIALS_ADC.equalsIgnoreCase(credentials) || isExternalAccount(credentials);
    }

    /** Keeps inline credentials out of logs. */
    @Override
    public String toString() {
        return "FcmSettings[projectId=" + projectId
                + ", credentials=" + describeCredentials(credentials)
                + ", validateOnly=" + validateOnly
                + ", dryRun=" + dryRun
                + ", timeout=" + timeout
                + ", timeoutClassification=" + timeoutClassification
                + ", concurrency=" + concurrency
                + ", multiTokenPolicy=" + multiTokenPolicy
                + ", maxTokens=" + maxTokens
                + ", logTokenHash=" + logTokenHash
                + ", tokenRefreshMargin=" + tokenRefreshMargin
                + ", endpoint=" + endpoint
                + ", tokenEndpoint=" + tokenEndpoint
                + ", httpTransport=" + httpTransport
                + ", androidDefaults=" + androidDefaults
                + ", apnsDefaults=" + apnsDefaults
                + ", webpushDefaults=" + webpushDefaults + "]";
    }

    static ProviderConfigurationException invalid(String reason) {
        return new ProviderConfigurationException(PROVIDER_NAME, CHANNEL, reason);
    }

    static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static String string(Map<String, Object> props, String key) {
        Object value = props.get(key);
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean bool(Map<String, Object> props, String key, boolean defaultValue) {
        Object value = props.get(key);
        if (isBlank(value)) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(s)) {
            return true;
        }
        if ("false".equalsIgnoreCase(s)) {
            return false;
        }
        throw invalid("'" + key + "' must be true or false, got '" + value + "'");
    }

    private static int positiveInt(Map<String, Object> props, String key, int defaultValue) {
        Object value = props.get(key);
        if (isBlank(value)) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = value instanceof Number n ? Math.toIntExact(n.longValue()) : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException | ArithmeticException _) {
            throw invalid("'" + key + "' must be a whole number, got '" + value + "'");
        }
        if (parsed < 1) {
            throw invalid("'" + key + "' must be at least 1, got '" + value + "'");
        }
        return parsed;
    }

    private static <E extends Enum<E>> E enumValue(Map<String, Object> props, String key, Class<E> type,
                                                   E defaultValue) {
        Object value = props.get(key);
        if (isBlank(value)) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            List<String> allowed = Arrays.stream(type.getEnumConstants())
                    .map(e -> e.name().toLowerCase(Locale.ROOT)).toList();
            throw invalid("'" + key + "' must be one of " + allowed + ", got '" + value + "'");
        }
    }

    private static Duration positiveDuration(Map<String, Object> props, String key, Duration defaultValue) {
        Duration duration = duration(props, key, defaultValue);
        if (duration.isZero() || duration.isNegative()) {
            throw invalid("'" + key + "' must be positive, got '" + props.get(key) + "'");
        }
        return duration;
    }

    private static Duration refreshMargin(Map<String, Object> props) {
        Duration margin = duration(props, TOKEN_REFRESH_MARGIN, DEFAULT_TOKEN_REFRESH_MARGIN);
        if (margin.isNegative() || margin.compareTo(MAX_TOKEN_REFRESH_MARGIN) > 0) {
            throw invalid("'" + TOKEN_REFRESH_MARGIN + "' must be between 0 and " + MAX_TOKEN_REFRESH_MARGIN
                    + ", got '" + props.get(TOKEN_REFRESH_MARGIN) + "'");
        }
        return margin;
    }

    private static Duration duration(Map<String, Object> props, String key, Duration defaultValue) {
        Object value = props.get(key);
        if (isBlank(value)) {
            return defaultValue;
        }
        if (value instanceof Duration d) {
            return d;
        }
        String s = String.valueOf(value).trim();
        Matcher simple = SIMPLE_DURATION.matcher(s.toLowerCase(Locale.ROOT));
        if (simple.matches()) {
            long amount = Long.parseLong(simple.group(1));
            return switch (simple.group(2)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            };
        }
        try {
            return Duration.parse(s);
        } catch (DateTimeParseException _) {
            throw invalid("'" + key + "' must be a duration such as 10s, 500ms or PT10S, got '" + value + "'");
        }
    }

    private static URI endpoint(Map<String, Object> props, String key, URI defaultValue) {
        String value = string(props, key);
        if (value == null) {
            return defaultValue;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException _) {
            throw invalid("'" + key + "' is not a valid URI: '" + value + "'");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw invalid("'" + key + "' must be an absolute https URI, got '" + value + "'");
        }
        if (uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw invalid("'" + key + "' must not have user info, a query or a fragment, got '" + value + "'");
        }
        boolean https = "https".equals(scheme);
        boolean loopbackHttp = "http".equals(scheme) && isLoopback(uri.getHost());
        if (!https && !loopbackHttp) {
            throw invalid("'" + key + "' must use https; plain http is accepted only for a loopback address "
                    + "(localhost, 127.0.0.1, [::1]), got '" + value + "'");
        }
        String text = uri.toString();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return URI.create(text);
    }

    private static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(h) || "[::1]".equals(h) || "::1".equals(h) || h.matches("127(\\.\\d{1,3}){3}");
    }

    /**
     * The {@code android}, {@code apns} and {@code webpush} tenant defaults, from
     * nested maps (YAML) or dotted keys ({@code android.priority}); dotted keys win.
     * Header values become strings, as FCM requires.
     */
    private static ObjectNode platformDefaults(Map<String, Object> props) {
        Map<String, Object> nested = new LinkedHashMap<>();
        for (String platform : PLATFORMS) {
            if (props.get(platform) instanceof Map<?, ?> map) {
                nested.put(platform, map);
            } else if (props.get(platform) != null && !isBlank(props.get(platform))) {
                throw invalid("'" + platform + "' must be a map of FCM " + platform + " fields");
            }
        }
        ObjectNode root = FcmJson.MAPPER.valueToTree(nested);
        props.forEach((key, value) -> {
            int dot = key.indexOf('.');
            if (dot > 0 && PLATFORMS.contains(key.substring(0, dot)) && value != null) {
                String[] path = key.split("\\.");
                ObjectNode node = root;
                for (int i = 0; i < path.length - 1; i++) {
                    JsonNode child = node.get(path[i]);
                    node = child instanceof ObjectNode object ? object : node.putObject(path[i]);
                }
                node.set(path[path.length - 1], FcmJson.MAPPER.valueToTree(value));
            }
        });
        for (String platform : List.of("apns", "webpush")) {
            if (root.get(platform) instanceof ObjectNode block && block.get("headers") instanceof ObjectNode headers) {
                headers.properties().forEach(header -> {
                    if (!header.getValue().isValueNode()) {
                        throw invalid("'" + platform + ".headers." + header.getKey() + "' must be a single value");
                    }
                    headers.put(header.getKey(), header.getValue().asText());
                });
            }
        }
        if (root.get("android") instanceof ObjectNode android) {
            if (android.has("ttl")) {
                String ttl = android.get("ttl").asText();
                if (!FcmMessageMapper.isValidTtl(ttl)) {
                    throw invalid("'android.ttl' must be seconds with an 's' suffix, such as 3600s or 3.5s, got '"
                            + ttl + "'");
                }
                android.put("ttl", ttl);
            }
            if (android.has("priority")) {
                String priority = FcmMessageMapper.androidPriority(android.get("priority").asText());
                if (priority == null) {
                    throw invalid("'android.priority' must be normal or high, got '"
                            + android.get("priority").asText() + "'");
                }
                android.put("priority", priority);
            }
        }
        return root;
    }
}
