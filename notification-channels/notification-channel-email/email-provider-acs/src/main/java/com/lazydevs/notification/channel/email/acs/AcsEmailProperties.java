package com.lazydevs.notification.channel.email.acs;

import com.lazydevs.notification.api.exception.ProviderConfigurationException;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Settings of one {@link AcsEmailProvider} instance, parsed from the per-tenant
 * provider properties map that {@code ProviderRegistry} hands to
 * {@link AcsEmailProvider#configure(Map)}.
 *
 * <p>This is a plain value object: there is no global Spring binding, because
 * every tenant configures its own provider instance.
 * Recognised keys (unknown keys are ignored, so channel-level settings merged
 * in by the registry do no harm):
 * <ul>
 *   <li>{@code endpoint} - ACS resource endpoint, used with {@code credential}.</li>
 *   <li>{@code connection-string} - wins over {@code endpoint} when both are set.</li>
 *   <li>{@code credential} - {@code default} for {@code DefaultAzureCredential}
 *       (needs azure-identity), otherwise the name of a {@code TokenCredential} bean.</li>
 *   <li>{@code sender} - sender address; falls back to the channel-level {@code from-address}.</li>
 *   <li>{@code reply-to} - default reply-to addresses, comma-separated or a list.</li>
 *   <li>{@code send-mode} - {@code wait} (default) or {@code submit}.</li>
 *   <li>{@code wait-timeout} - ISO-8601 duration, default {@code PT60S}.</li>
 *   <li>{@code sdk-retries} - retries inside the Azure SDK, default {@code 0}.</li>
 *   <li>{@code user-engagement-tracking-disabled} - optional boolean.</li>
 * </ul>
 *
 * @param endpoint                       ACS endpoint, may be {@code null}
 * @param connectionString               ACS connection string, may be {@code null}
 * @param credential                     credential selector, may be {@code null}
 * @param sender                         sender address, never blank
 * @param replyTo                        default reply-to addresses, never {@code null}
 * @param sendMode                       how long {@code send} waits for ACS
 * @param waitTimeout                    upper bound for {@link SendMode#WAIT}
 * @param sdkRetries                     retries performed by the Azure SDK itself
 * @param userEngagementTrackingDisabled {@code null} keeps the ACS resource default
 */
public record AcsEmailProperties(
        String endpoint,
        String connectionString,
        String credential,
        String sender,
        List<String> replyTo,
        SendMode sendMode,
        Duration waitTimeout,
        int sdkRetries,
        Boolean userEngagementTrackingDisabled) {

    static final String PROVIDER_NAME = "acs";
    static final String CHANNEL = "EMAIL";

    public static final String ENDPOINT = "endpoint";
    public static final String CONNECTION_STRING = "connection-string";
    public static final String CREDENTIAL = "credential";
    public static final String SENDER = "sender";
    /** Channel-level sender key shared with the SMTP and SES providers. */
    public static final String FROM_ADDRESS = "from-address";
    public static final String REPLY_TO = "reply-to";
    public static final String SEND_MODE = "send-mode";
    public static final String WAIT_TIMEOUT = "wait-timeout";
    public static final String SDK_RETRIES = "sdk-retries";
    public static final String USER_ENGAGEMENT_TRACKING_DISABLED = "user-engagement-tracking-disabled";

    /** Default upper bound for {@link SendMode#WAIT}. */
    public static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(60);

    /**
     * How long {@link AcsEmailProvider#send} waits for ACS.
     */
    public enum SendMode {
        /** Poll until ACS reports a final status (or {@code wait-timeout} elapses). */
        WAIT,
        /** Return once ACS accepted the request; costs one status poll to read the operation id. */
        SUBMIT
    }

    /**
     * Canonical constructor: normalises the reply-to list.
     */
    public AcsEmailProperties {
        replyTo = replyTo == null ? List.of() : List.copyOf(replyTo);
    }

    /**
     * @return {@code true} when a connection string is configured (it wins over endpoint).
     */
    public boolean hasConnectionString() {
        return hasText(connectionString);
    }

    /**
     * Parse and validate the provider properties map.
     *
     * @param properties merged channel and provider properties (may be {@code null})
     * @return the validated settings
     * @throws ProviderConfigurationException when a value is missing or malformed
     */
    public static AcsEmailProperties fromMap(Map<String, Object> properties) {
        Map<String, Object> props = properties == null ? Map.of() : properties;

        String endpoint = string(props, ENDPOINT);
        String connectionString = string(props, CONNECTION_STRING);
        if (!hasText(endpoint) && !hasText(connectionString)) {
            throw invalid("set '" + CONNECTION_STRING + "', or '" + ENDPOINT + "' together with '"
                    + CREDENTIAL + "'");
        }

        String sender = string(props, SENDER);
        if (!hasText(sender)) {
            sender = string(props, FROM_ADDRESS);
        }
        if (!hasText(sender)) {
            throw invalid("'" + SENDER + "' is required (the verified MailFrom address of the ACS domain, "
                    + "for example DoNotReply@<domain>); the channel-level '" + FROM_ADDRESS
                    + "' is used when 'sender' is absent");
        }

        return new AcsEmailProperties(
                endpoint,
                connectionString,
                string(props, CREDENTIAL),
                sender,
                stringList(props.get(REPLY_TO)),
                sendMode(props.get(SEND_MODE)),
                waitTimeout(props.get(WAIT_TIMEOUT)),
                sdkRetries(props.get(SDK_RETRIES)),
                optionalBoolean(props.get(USER_ENGAGEMENT_TRACKING_DISABLED)));
    }

    /**
     * Keeps the connection string (it embeds the access key) out of logs.
     */
    @Override
    public String toString() {
        return "AcsEmailProperties[endpoint=" + endpoint
                + ", connectionString=" + (hasConnectionString() ? "****" : null)
                + ", credential=" + credential
                + ", sender=" + sender
                + ", replyTo=" + replyTo
                + ", sendMode=" + sendMode
                + ", waitTimeout=" + waitTimeout
                + ", sdkRetries=" + sdkRetries
                + ", userEngagementTrackingDisabled=" + userEngagementTrackingDisabled + "]";
    }

    static ProviderConfigurationException invalid(String reason) {
        return new ProviderConfigurationException(PROVIDER_NAME, CHANNEL, reason);
    }

    static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String string(Map<String, Object> props, String key) {
        Object value = props.get(key);
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    /**
     * Accepts a comma-separated string, a collection, or the index-keyed map
     * ({@code {0=a, 1=b}}) that binding a YAML list into
     * {@code Map<String, Object>} can produce.
     */
    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Collection<?> c) {
            c.forEach(v -> addSplit(out, v));
        } else if (value instanceof Map<?, ?> m) {
            Map<Integer, Object> ordered = new TreeMap<>();
            m.forEach((k, v) -> ordered.put(index(k), v));
            ordered.values().forEach(v -> addSplit(out, v));
        } else {
            addSplit(out, value);
        }
        return out;
    }

    private static int index(Object key) {
        try {
            return Integer.parseInt(String.valueOf(key).trim());
        } catch (NumberFormatException _) {
            throw invalid("'" + REPLY_TO + "' must be a comma-separated string or a list of addresses");
        }
    }

    private static void addSplit(List<String> out, Object value) {
        if (value == null) {
            return;
        }
        for (String part : String.valueOf(value).split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
    }

    private static SendMode sendMode(Object value) {
        if (isBlank(value)) {
            return SendMode.WAIT;
        }
        try {
            return SendMode.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            throw invalid("'" + SEND_MODE + "' must be 'wait' or 'submit', got '" + value + "'");
        }
    }

    private static Duration waitTimeout(Object value) {
        if (isBlank(value)) {
            return DEFAULT_WAIT_TIMEOUT;
        }
        Duration timeout;
        if (value instanceof Duration d) {
            timeout = d;
        } else {
            try {
                timeout = Duration.parse(String.valueOf(value).trim());
            } catch (DateTimeParseException _) {
                throw invalid("'" + WAIT_TIMEOUT + "' must be an ISO-8601 duration such as PT60S, got '"
                        + value + "'");
            }
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw invalid("'" + WAIT_TIMEOUT + "' must be positive, got '" + value + "'");
        }
        return timeout;
    }

    private static int sdkRetries(Object value) {
        if (isBlank(value)) {
            return 0;
        }
        int retries;
        try {
            retries = value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException _) {
            throw invalid("'" + SDK_RETRIES + "' must be a whole number, got '" + value + "'");
        }
        if (retries < 0) {
            throw invalid("'" + SDK_RETRIES + "' must not be negative, got '" + value + "'");
        }
        return retries;
    }

    private static Boolean optionalBoolean(Object value) {
        if (isBlank(value)) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(s)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s)) {
            return Boolean.FALSE;
        }
        throw invalid("'" + USER_ENGAGEMENT_TRACKING_DISABLED + "' must be true or false, got '" + value + "'");
    }
}
