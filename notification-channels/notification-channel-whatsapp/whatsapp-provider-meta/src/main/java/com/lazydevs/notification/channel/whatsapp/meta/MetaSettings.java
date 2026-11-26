package com.lazydevs.notification.channel.whatsapp.meta;

import com.lazydevs.notification.api.exception.ProviderConfigurationException;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings of one {@link MetaWhatsAppProvider} instance, parsed from the
 * per-tenant provider properties map that {@code ProviderRegistry} hands to
 * {@link MetaWhatsAppProvider#configure(Map)}.
 *
 * <p>Recognised keys (unknown keys are ignored):
 * <ul>
 *   <li>{@code base-url} - default {@value #DEFAULT_BASE_URL}; https, or http to a
 *       loopback address only (tests).</li>
 *   <li>{@code api-version} - Graph API version, default {@value #DEFAULT_API_VERSION}.
 *       Pinned on purpose: a new library release may move the default, your
 *       configuration decides when you move.</li>
 *   <li>{@code timeout} - per request, default 10s.</li>
 *   <li>{@code phone-number-id}, {@code access-token}, {@code waba-id} - the static
 *       credentials, used when there is no {@link MetaCredentialsProvider} bean.</li>
 *   <li>{@code pair-rate-limit-retry-after} - the least delay before retrying a
 *       pair-rate-limited send (code 131056), default 6s.</li>
 * </ul>
 * Durations are ISO-8601 ({@code PT10S}) or a number with a unit
 * ({@code 500ms}, {@code 10s}, {@code 5m}, {@code 1h}).
 *
 * @param baseUrl                 Graph API base URL, without a trailing slash
 * @param apiVersion              Graph API version, for example {@code v25.0}
 * @param timeout                 per-request timeout
 * @param pairRateLimitRetryAfter least retry delay after code 131056
 * @param credentials             the static credentials, {@code null} when not configured
 * @since 1.4.0
 */
public record MetaSettings(
        URI baseUrl,
        String apiVersion,
        Duration timeout,
        Duration pairRateLimitRetryAfter,
        MetaCredentials credentials) {

    static final String PROVIDER_NAME = "meta";
    static final String CHANNEL = "WHATSAPP";

    public static final String BASE_URL = "base-url";
    public static final String API_VERSION = "api-version";
    public static final String TIMEOUT_KEY = "timeout";
    public static final String PHONE_NUMBER_ID = "phone-number-id";
    public static final String ACCESS_TOKEN = "access-token";
    public static final String WABA_ID = "waba-id";
    public static final String PAIR_RATE_LIMIT_RETRY_AFTER = "pair-rate-limit-retry-after";

    public static final String DEFAULT_BASE_URL = "https://graph.facebook.com";
    /** The Graph API version this release was verified against. */
    public static final String DEFAULT_API_VERSION = "v25.0";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_PAIR_RATE_LIMIT_RETRY_AFTER = Duration.ofSeconds(6);

    private static final Pattern API_VERSION_PATTERN = Pattern.compile("v\\d{1,3}\\.\\d{1,3}");
    private static final Pattern SIMPLE_DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h)");

    /**
     * Defaults for everything, and no static credentials.
     *
     * @return the settings
     */
    public static MetaSettings defaults() {
        return fromMap(Map.of());
    }

    /**
     * Parse and validate the provider properties map.
     *
     * @param properties merged channel and provider properties (may be {@code null})
     * @return the validated settings
     * @throws ProviderConfigurationException when a value is malformed
     */
    public static MetaSettings fromMap(Map<String, Object> properties) {
        Map<String, Object> props = properties == null ? Map.of() : properties;
        URI baseUrl = baseUrl(stringOr(props, BASE_URL, DEFAULT_BASE_URL));
        String apiVersion = stringOr(props, API_VERSION, DEFAULT_API_VERSION);
        if (!API_VERSION_PATTERN.matcher(apiVersion).matches()) {
            throw invalid("'" + API_VERSION + "' must look like v25.0, got '" + apiVersion + "'");
        }
        Duration timeout = duration(props, TIMEOUT_KEY, DEFAULT_TIMEOUT);
        if (timeout.isZero() || timeout.isNegative()) {
            throw invalid("'" + TIMEOUT_KEY + "' must be positive");
        }
        Duration pairFloor = duration(props, PAIR_RATE_LIMIT_RETRY_AFTER, DEFAULT_PAIR_RATE_LIMIT_RETRY_AFTER);
        if (pairFloor.isNegative()) {
            throw invalid("'" + PAIR_RATE_LIMIT_RETRY_AFTER + "' must not be negative");
        }
        return new MetaSettings(baseUrl, apiVersion, timeout, pairFloor, credentials(props));
    }

    /** @return the static credentials, if configured */
    public Optional<MetaCredentials> staticCredentials() {
        return Optional.ofNullable(credentials);
    }

    /**
     * @param phoneNumberId the sender's phone number id
     * @return {@code <base-url>/<api-version>/<phone-number-id>/messages}
     */
    URI messagesUri(String phoneNumberId) {
        return URI.create(baseUrl + "/" + apiVersion + "/" + phoneNumberId + "/messages");
    }

    private static MetaCredentials credentials(Map<String, Object> props) {
        String token = string(props, ACCESS_TOKEN);
        String phoneNumberId = string(props, PHONE_NUMBER_ID);
        if (token == null && phoneNumberId == null) {
            return null;
        }
        if (token == null || phoneNumberId == null) {
            throw invalid("set both '" + ACCESS_TOKEN + "' and '" + PHONE_NUMBER_ID + "', or neither and declare a"
                    + " MetaCredentialsProvider bean");
        }
        try {
            return new MetaCredentials(token, phoneNumberId, string(props, WABA_ID));
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
    }

    private static URI baseUrl(String value) {
        String trimmed = value.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException _) {
            throw invalid("'" + BASE_URL + "' is not a URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
            throw invalid("'" + BASE_URL + "' must be an absolute URL without query or fragment");
        }
        if (!"https".equals(scheme) && !("http".equals(scheme) && isLoopback(uri.getHost()))) {
            throw invalid("'" + BASE_URL + "' must use https (http only to a loopback address)");
        }
        return uri;
    }

    private static boolean isLoopback(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        try {
            // Only literal addresses reach here in practice; a name is not resolved for anything else.
            return host.chars().allMatch(c -> Character.isDigit(c) || c == '.' || c == ':' || c == '[' || c == ']')
                    && InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException _) {
            return false;
        }
    }

    private static Duration duration(Map<String, Object> props, String key, Duration fallback) {
        Object value = props.get(key);
        switch (value) {
            case null -> {
                return fallback;
            }
            case Duration d -> {
                return d;
            }
            case Number n -> {
                return Duration.ofSeconds(n.longValue());
            }
            default -> {
                String text = value.toString().strip();
                if (text.isEmpty()) {
                    return fallback;
                }
                Matcher simple = SIMPLE_DURATION.matcher(text.toLowerCase(Locale.ROOT));
                if (simple.matches()) {
                    long amount = Long.parseLong(simple.group(1));
                    return switch (simple.group(2)) {
                        case "ms" -> Duration.ofMillis(amount);
                        case "s" -> Duration.ofSeconds(amount);
                        case "m" -> Duration.ofMinutes(amount);
                        default -> Duration.ofHours(amount);
                    };
                }
                try {
                    return Duration.parse(text);
                } catch (DateTimeParseException _) {
                    throw invalid("'" + key + "' is not a duration: '" + text + "'");
                }
            }
        }
    }

    private static String stringOr(Map<String, Object> props, String key, String fallback) {
        String value = string(props, key);
        return value == null ? fallback : value;
    }

    private static String string(Map<String, Object> props, String key) {
        Object value = props.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }

    static ProviderConfigurationException invalid(String reason) {
        return new ProviderConfigurationException(PROVIDER_NAME, CHANNEL, reason);
    }

    /** Never the access token. */
    @Override
    public String toString() {
        return "MetaSettings[baseUrl=" + baseUrl + ", apiVersion=" + apiVersion + ", timeout=" + timeout
                + ", pairRateLimitRetryAfter=" + pairRateLimitRetryAfter + ", credentials="
                + (credentials == null ? "none" : credentials) + "]";
    }
}
