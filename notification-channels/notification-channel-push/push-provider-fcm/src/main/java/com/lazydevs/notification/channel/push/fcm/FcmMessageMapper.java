package com.lazydevs.notification.channel.push.fcm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.PushRecipient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps a {@link NotificationRequest} with a {@link PushRecipient} onto an FCM
 * HTTP v1 {@code Message} (Jackson tree, no bound classes).
 *
 * <ul>
 *   <li>Target: exactly one of {@code deviceToken}, {@code deviceTokens},
 *       {@code fid}, {@code topic} or {@code condition}; anything else fails with
 *       {@value #CODE_INVALID_TARGET_SPEC}.</li>
 *   <li>{@code notification}: {@code title} is the rendered subject, else
 *       {@code PushRecipient.title}; {@code body} is the rendered text, else
 *       {@code PushRecipient.body}; {@code image} is {@code imageUrl}.</li>
 *   <li>{@code data}: string values; reserved keys ({@code from},
 *       {@code message_type}, {@code google.*}, {@code gcm.*}) fail with
 *       {@value #CODE_INVALID_DATA_KEY}.</li>
 *   <li>{@code badge} becomes {@code apns.payload.aps.badge}; {@code sound} becomes
 *       {@code apns.payload.aps.sound} and {@code android.notification.sound};
 *       {@code clickAction} becomes {@code android.notification.click_action},
 *       {@code apns.payload.aps.category} and, for an https URL,
 *       {@code webpush.fcm_options.link}.</li>
 *   <li>Platform blocks start from the tenant defaults ({@code android.*},
 *       {@code apns.*}, {@code webpush.*}); the fields above and then the
 *       whitelisted {@link NotificationRequest#getMetadata()} overrides are merged
 *       over them. Any other {@code fcm.*} metadata key fails with
 *       {@value #CODE_INVALID_OVERRIDE}.</li>
 *   <li>Payload size: the UTF-8 bytes of every data key and value plus the
 *       notification title, body and image may not exceed {@value #MAX_PAYLOAD_BYTES}
 *       ({@value #MAX_TOPIC_PAYLOAD_BYTES} for a topic or condition), else
 *       {@value #CODE_PAYLOAD_TOO_LARGE}. FCM's own check stays authoritative.</li>
 * </ul>
 */
final class FcmMessageMapper {

    static final String TARGET_TOKEN = "token";
    /** The FCM v1 field for a Firebase installation id. */
    static final String TARGET_FID = "fid";
    static final String TARGET_TOPIC = "topic";
    static final String TARGET_CONDITION = "condition";

    static final int MAX_PAYLOAD_BYTES = 4096;
    static final int MAX_TOPIC_PAYLOAD_BYTES = 2048;
    /** FCM's maximum time to live: four weeks. */
    static final long MAX_TTL_SECONDS = 2_419_200L;
    /** APNs limit for {@code apns-collapse-id}. */
    static final int MAX_APNS_COLLAPSE_ID_BYTES = 64;

    static final String CODE_INVALID_TARGET_SPEC = "FCM_INVALID_TARGET_SPEC";
    static final String CODE_TOO_MANY_TOKENS = "FCM_TOO_MANY_TOKENS";
    static final String CODE_PAYLOAD_TOO_LARGE = "FCM_PAYLOAD_TOO_LARGE";
    static final String CODE_INVALID_DATA_KEY = "FCM_INVALID_DATA_KEY";
    static final String CODE_INVALID_OVERRIDE = "FCM_INVALID_OVERRIDE";

    /** Prefix of every {@code NotificationRequest.metadata} key the mapper reads. */
    static final String OVERRIDE_PREFIX = "fcm.";
    static final String OVERRIDE_ANDROID_PRIORITY = "fcm.android.priority";
    static final String OVERRIDE_ANDROID_TTL = "fcm.android.ttl";
    static final String OVERRIDE_ANDROID_COLLAPSE_KEY = "fcm.android.collapse_key";
    static final String OVERRIDE_APNS_PRIORITY = "fcm.apns.headers.apns-priority";
    static final String OVERRIDE_APNS_EXPIRATION = "fcm.apns.headers.apns-expiration";
    static final String OVERRIDE_APNS_COLLAPSE_ID = "fcm.apns.headers.apns-collapse-id";
    static final String OVERRIDE_WEBPUSH_TTL = "fcm.webpush.headers.TTL";

    /** Every metadata key the mapper accepts, in documentation order. */
    static final List<String> OVERRIDE_KEYS = List.of(OVERRIDE_ANDROID_PRIORITY, OVERRIDE_ANDROID_TTL,
            OVERRIDE_ANDROID_COLLAPSE_KEY, OVERRIDE_APNS_PRIORITY, OVERRIDE_APNS_EXPIRATION,
            OVERRIDE_APNS_COLLAPSE_ID, OVERRIDE_WEBPUSH_TTL);

    private static final Set<String> RESERVED_DATA_KEYS = Set.of("from", "message_type");
    private static final List<String> RESERVED_DATA_PREFIXES = List.of("google.", "gcm.");
    private static final Pattern TTL = Pattern.compile("(\\d+)(\\.\\d{1,9})?s");
    private static final Pattern TOPIC = Pattern.compile("[a-zA-Z0-9\\-_.~%]{1,900}");
    private static final Pattern DIGITS = Pattern.compile("\\d{1,19}");
    private static final Set<String> APNS_PRIORITIES = Set.of("1", "5", "10");

    private final FcmSettings settings;

    FcmMessageMapper(FcmSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Where a message goes: one FCM target field and its value, or several device tokens.
     *
     * @param field  {@link #TARGET_TOKEN}, {@link #TARGET_FID}, {@link #TARGET_TOPIC} or {@link #TARGET_CONDITION}
     * @param values one value, or the de-duplicated tokens of {@code deviceTokens}
     * @param multi  whether the recipient used {@code deviceTokens}
     */
    record Target(String field, List<String> values, boolean multi) {

        /** @return whether the target identifies one device (token or installation id) */
        boolean isDevice() {
            return TARGET_TOKEN.equals(field) || TARGET_FID.equals(field);
        }

        int payloadLimit() {
            return isDevice() ? MAX_PAYLOAD_BYTES : MAX_TOPIC_PAYLOAD_BYTES;
        }
    }

    /** The request cannot become an FCM message; retrying will not help. */
    static final class InvalidMessageException extends Exception {

        private static final long serialVersionUID = 1L;

        private final String code;

        InvalidMessageException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    Target target(PushRecipient recipient) throws InvalidMessageException {
        List<String> named = new ArrayList<>();
        if (hasText(recipient.deviceToken())) {
            named.add("deviceToken");
        }
        if (recipient.deviceTokens() != null && !recipient.deviceTokens().isEmpty()) {
            named.add("deviceTokens");
        }
        if (hasText(recipient.fid())) {
            named.add("fid");
        }
        if (hasText(recipient.topic())) {
            named.add("topic");
        }
        if (hasText(recipient.condition())) {
            named.add("condition");
        }
        if (named.size() != 1) {
            throw new InvalidMessageException(CODE_INVALID_TARGET_SPEC, named.isEmpty()
                    ? "the push recipient names no target; set exactly one of deviceToken, deviceTokens, fid,"
                    + " topic or condition"
                    : "the push recipient names " + named.size() + " targets " + named
                    + "; set exactly one of deviceToken, deviceTokens, fid, topic or condition");
        }
        return switch (named.getFirst()) {
            case "deviceToken" -> new Target(TARGET_TOKEN, List.of(recipient.deviceToken().strip()), false);
            case "deviceTokens" -> new Target(TARGET_TOKEN, tokens(recipient.deviceTokens()), true);
            case "fid" -> new Target(TARGET_FID, List.of(recipient.fid().strip()), false);
            case "topic" -> new Target(TARGET_TOPIC, List.of(topic(recipient.topic())), false);
            default -> new Target(TARGET_CONDITION, List.of(recipient.condition().strip()), false);
        };
    }

    private List<String> tokens(List<String> deviceTokens) throws InvalidMessageException {
        Set<String> unique = new LinkedHashSet<>();
        for (String token : deviceTokens) {
            if (!hasText(token)) {
                throw new InvalidMessageException(CODE_INVALID_TARGET_SPEC, "deviceTokens contains a blank token");
            }
            unique.add(token.strip());
        }
        if (unique.size() > settings.maxTokens()) {
            throw new InvalidMessageException(CODE_TOO_MANY_TOKENS, "deviceTokens has " + unique.size()
                    + " tokens; this tenant accepts at most " + settings.maxTokens() + " ('"
                    + FcmSettings.MAX_TOKENS + "')");
        }
        return List.copyOf(unique);
    }

    private static String topic(String topic) throws InvalidMessageException {
        String value = topic.strip();
        if (value.startsWith("/topics/")) {
            value = value.substring("/topics/".length());
        }
        if (!TOPIC.matcher(value).matches()) {
            throw new InvalidMessageException(CODE_INVALID_TARGET_SPEC, "topic '" + value
                    + "' may only contain letters, digits and -_.~%");
        }
        return value;
    }

    /**
     * The message without its target field.
     */
    ObjectNode message(NotificationRequest request, RenderedContent content, PushRecipient recipient,
                       Target target) throws InvalidMessageException {
        ObjectNode message = FcmJson.MAPPER.createObjectNode();

        String title = content != null && hasText(content.subject()) ? content.subject() : recipient.title();
        String body = content != null && content.hasText() ? content.textBody() : recipient.body();
        ObjectNode notification = FcmJson.MAPPER.createObjectNode();
        putText(notification, "title", title);
        putText(notification, "body", body);
        putText(notification, "image", recipient.imageUrl());
        if (!notification.isEmpty()) {
            message.set("notification", notification);
        }

        ObjectNode data = data(recipient.data());
        if (!data.isEmpty()) {
            message.set("data", data);
        }

        ObjectNode android = orEmpty(settings.androidDefaults());
        ObjectNode apns = orEmpty(settings.apnsDefaults());
        ObjectNode webpush = orEmpty(settings.webpushDefaults());

        if (recipient.badge() != null) {
            if (recipient.badge() < 0) {
                throw new InvalidMessageException(CODE_INVALID_OVERRIDE, "badge must not be negative");
            }
            aps(apns).put("badge", recipient.badge());
        }
        if (hasText(recipient.sound())) {
            aps(apns).put("sound", recipient.sound());
            object(android, "notification").put("sound", recipient.sound());
        }
        if (hasText(recipient.clickAction())) {
            String clickAction = recipient.clickAction().strip();
            object(android, "notification").put("click_action", clickAction);
            aps(apns).put("category", clickAction);
            if (clickAction.regionMatches(true, 0, "https://", 0, "https://".length())) {
                object(webpush, "fcm_options").put("link", clickAction);
            }
        }

        applyOverrides(request.getMetadata(), android, apns, webpush);

        if (!android.isEmpty()) {
            message.set("android", android);
        }
        if (!apns.isEmpty()) {
            message.set("apns", apns);
        }
        if (!webpush.isEmpty()) {
            message.set("webpush", webpush);
        }

        int size = payloadBytes(message);
        if (size > target.payloadLimit()) {
            throw new InvalidMessageException(CODE_PAYLOAD_TOO_LARGE, "the notification and data payload is "
                    + size + " bytes; FCM accepts at most " + target.payloadLimit() + " bytes for a "
                    + target.field() + " target");
        }
        return message;
    }

    /** {@code message} with its target field first, as a new object. */
    static ObjectNode addressed(ObjectNode message, String field, String value) {
        ObjectNode addressed = FcmJson.MAPPER.createObjectNode();
        addressed.put(field, value);
        addressed.setAll(message.deepCopy());
        return addressed;
    }

    /** The {@code messages:send} request body. */
    static ObjectNode envelope(ObjectNode addressedMessage, boolean validateOnly) {
        ObjectNode envelope = FcmJson.MAPPER.createObjectNode();
        if (validateOnly) {
            envelope.put("validate_only", true);
        }
        envelope.set("message", addressedMessage);
        return envelope;
    }

    /** UTF-8 bytes of every data key and value plus the notification title, body and image. */
    static int payloadBytes(ObjectNode message) {
        int size = 0;
        JsonNode data = message.get("data");
        if (data != null) {
            for (Map.Entry<String, JsonNode> entry : data.properties()) {
                size += utf8(entry.getKey()) + utf8(entry.getValue().asText());
            }
        }
        JsonNode notification = message.get("notification");
        if (notification != null) {
            for (Map.Entry<String, JsonNode> entry : notification.properties()) {
                size += utf8(entry.getValue().asText());
            }
        }
        return size;
    }

    private static ObjectNode data(Map<String, String> data) throws InvalidMessageException {
        ObjectNode node = FcmJson.MAPPER.createObjectNode();
        if (data == null) {
            return node;
        }
        for (Map.Entry<String, String> entry : data.entrySet()) {
            String key = entry.getKey();
            if (!hasText(key)) {
                throw new InvalidMessageException(CODE_INVALID_DATA_KEY, "data has a blank key");
            }
            String lower = key.toLowerCase(Locale.ROOT);
            if (RESERVED_DATA_KEYS.contains(lower) || RESERVED_DATA_PREFIXES.stream().anyMatch(lower::startsWith)) {
                throw new InvalidMessageException(CODE_INVALID_DATA_KEY, "data key '" + key
                        + "' is reserved by FCM (from, message_type, google.*, gcm.*)");
            }
            if (entry.getValue() != null) {
                node.put(key, entry.getValue());
            }
        }
        return node;
    }

    private static void applyOverrides(Map<String, String> metadata, ObjectNode android, ObjectNode apns,
                                       ObjectNode webpush) throws InvalidMessageException {
        if (metadata == null) {
            return;
        }
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(OVERRIDE_PREFIX)) {
                continue;
            }
            String value = entry.getValue() == null ? null : entry.getValue().strip();
            if (!hasText(value)) {
                throw new InvalidMessageException(CODE_INVALID_OVERRIDE, "metadata '" + key + "' is blank");
            }
            switch (key) {
                case OVERRIDE_ANDROID_PRIORITY -> {
                    String priority = androidPriority(value);
                    if (priority == null) {
                        throw invalidOverride(key, value, "normal or high");
                    }
                    android.put("priority", priority);
                }
                case OVERRIDE_ANDROID_TTL -> {
                    if (!isValidTtl(value)) {
                        throw invalidOverride(key, value, "seconds with an 's' suffix, such as 3600s or 3.5s,"
                                + " at most " + MAX_TTL_SECONDS + "s");
                    }
                    android.put("ttl", value);
                }
                case OVERRIDE_ANDROID_COLLAPSE_KEY -> android.put("collapse_key", value);
                case OVERRIDE_APNS_PRIORITY -> {
                    if (!APNS_PRIORITIES.contains(value)) {
                        throw invalidOverride(key, value, "10, 5 or 1");
                    }
                    object(apns, "headers").put("apns-priority", value);
                }
                case OVERRIDE_APNS_EXPIRATION -> {
                    if (!DIGITS.matcher(value).matches()) {
                        throw invalidOverride(key, value, "a UNIX epoch time in seconds, or 0");
                    }
                    object(apns, "headers").put("apns-expiration", value);
                }
                case OVERRIDE_APNS_COLLAPSE_ID -> {
                    if (utf8(value) > MAX_APNS_COLLAPSE_ID_BYTES) {
                        throw invalidOverride(key, value, "at most " + MAX_APNS_COLLAPSE_ID_BYTES + " bytes");
                    }
                    object(apns, "headers").put("apns-collapse-id", value);
                }
                case OVERRIDE_WEBPUSH_TTL -> {
                    if (!DIGITS.matcher(value).matches()) {
                        throw invalidOverride(key, value, "a whole number of seconds");
                    }
                    object(webpush, "headers").put("TTL", value);
                }
                default -> throw new InvalidMessageException(CODE_INVALID_OVERRIDE, "metadata key '" + key
                        + "' is not an FCM override; supported: " + OVERRIDE_KEYS);
            }
        }
    }

    private static InvalidMessageException invalidOverride(String key, String value, String expected) {
        return new InvalidMessageException(CODE_INVALID_OVERRIDE, "metadata '" + key + "' must be " + expected
                + ", got '" + value + "'");
    }

    /** @return {@code NORMAL} or {@code HIGH} for a case-insensitive match, else {@code null} */
    static String androidPriority(String value) {
        String upper = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        return "NORMAL".equals(upper) || "HIGH".equals(upper) ? upper : null;
    }

    /** @return whether {@code value} is an FCM duration such as {@code 3.5s}, at most four weeks */
    static boolean isValidTtl(String value) {
        if (value == null || !TTL.matcher(value).matches()) {
            return false;
        }
        return new BigDecimal(value.substring(0, value.length() - 1)).compareTo(BigDecimal.valueOf(MAX_TTL_SECONDS)) <= 0;
    }

    private static ObjectNode aps(ObjectNode apns) {
        return object(object(apns, "payload"), "aps");
    }

    private static ObjectNode object(ObjectNode parent, String field) {
        JsonNode existing = parent.get(field);
        return existing instanceof ObjectNode object ? object : parent.putObject(field);
    }

    private static ObjectNode orEmpty(ObjectNode node) {
        return node == null ? FcmJson.MAPPER.createObjectNode() : node;
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (hasText(value)) {
            node.put(field, value);
        }
    }

    private static int utf8(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
