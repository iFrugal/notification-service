package com.lazydevs.notification.channel.whatsapp.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.util.PiiMasking;
import com.lazydevs.notification.api.webhook.WebhookHandler;
import com.lazydevs.notification.api.webhook.WebhookRequest;
import com.lazydevs.notification.api.webhook.WebhookResponse;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Meta WhatsApp Cloud API webhook (DD-35, DD-36): the subscription handshake
 * and message status callbacks.
 *
 * <ul>
 *   <li>{@code GET} with {@code hub.mode=subscribe}: when {@code hub.verify_token}
 *       matches the configured verify token (constant-time), answers 200
 *       {@code text/plain} with {@code hub.challenge}; otherwise 403.</li>
 *   <li>{@code POST}: verifies {@code X-Hub-Signature-256: sha256=<hex>}, the
 *       HMAC-SHA256 of the raw body keyed with the app secret (constant-time),
 *       else 403. Then maps {@code entry[].changes[].value.statuses[]} onto
 *       delivery events and answers 200, also when nothing was mappable,
 *       because Meta retries any other answer for days. Only a body that is not
 *       a JSON object (after a valid signature) is 400.</li>
 * </ul>
 *
 * <table>
 *   <caption>Status mapping</caption>
 *   <tr><th>Meta {@code status}</th><th>{@link DeliveryStatus}</th></tr>
 *   <tr><td>{@code sent}</td><td>SENT</td></tr>
 *   <tr><td>{@code delivered}</td><td>DELIVERED</td></tr>
 *   <tr><td>{@code read}</td><td>READ (may arrive before DELIVERED)</td></tr>
 *   <tr><td>{@code failed} with error code 131026</td><td>BOUNCED</td></tr>
 *   <tr><td>{@code failed} otherwise</td><td>FAILED_AT_PROVIDER, reason "code title"</td></tr>
 *   <tr><td>anything else</td><td>UNKNOWN</td></tr>
 * </table>
 *
 * Each event has provider {@code meta} (whatever the handler id), the {@code wamid} as provider message
 * id, {@code wamid:status:timestamp} as provider event id (Meta redelivers;
 * listeners dedupe on it) and the attributes {@code status},
 * {@code phone_number_id}, {@code biz_opaque_callback_data} (the library request
 * id), {@code error_code}, {@code error_title}, {@code pricing_category} and
 * {@code conversation_category} when present. The recipient's number
 * ({@code recipient_id}) is never copied, nor logged. Inbound customer messages
 * ({@code value.messages}) are counted and ignored.
 *
 * <p>One instance serves one Meta app; register one per app, with different ids,
 * to serve several.
 *
 * @since 1.3.0
 */
@Slf4j
public class MetaWebhookHandler implements WebhookHandler {

    /** Default handler id. */
    public static final String DEFAULT_ID = "meta";

    /**
     * Provider name of every event, whatever the handler id: it matches
     * {@code NotificationResponse.provider()} of the send.
     */
    public static final String PROVIDER_NAME = MetaWhatsAppProvider.PROVIDER_NAME;

    /** Signature header Meta sends with every POST. */
    public static final String SIGNATURE_HEADER = "X-Hub-Signature-256";

    static final String ATTR_STATUS = "status";
    static final String ATTR_PHONE_NUMBER_ID = "phone_number_id";
    static final String ATTR_CALLBACK_DATA = "biz_opaque_callback_data";
    static final String ATTR_ERROR_CODE = "error_code";
    static final String ATTR_ERROR_TITLE = "error_title";
    static final String ATTR_PRICING_CATEGORY = "pricing_category";
    static final String ATTR_CONVERSATION_CATEGORY = "conversation_category";

    static final int CODE_UNDELIVERABLE = 131026;

    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final String HMAC = "HmacSHA256";
    private static final String MODE_SUBSCRIBE = "subscribe";
    private static final String FIELD_ERROR = "error";
    private static final String FIELD_STATUS = "status";
    private static final String CATEGORY = "category";
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private final String id;
    private final byte[] appSecret;
    private final byte[] verifyTokenDigest;
    private final boolean signatureVerification;
    private final Clock clock;

    /**
     * @param properties the handler settings
     * @throws IllegalStateException when signature verification is on without an app secret, or the id is malformed
     */
    public MetaWebhookHandler(MetaWebhookProperties properties) {
        this(properties, Clock.systemUTC());
    }

    MetaWebhookHandler(MetaWebhookProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "properties");
        this.id = properties.getId() == null ? DEFAULT_ID : properties.getId().strip();
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalStateException(MetaWebhookProperties.PREFIX + ".id must be lowercase letters, digits"
                    + " and '-', got '" + id + "'");
        }
        String secret = properties.getAppSecret();
        boolean hasSecret = secret != null && !secret.isBlank();
        if (properties.isSignatureVerification() && !hasSecret) {
            throw new IllegalStateException(MetaWebhookProperties.PREFIX + ".app-secret is required while"
                    + " signature-verification is on");
        }
        this.appSecret = hasSecret ? secret.strip().getBytes(StandardCharsets.UTF_8) : null;
        String token = properties.getVerifyToken();
        this.verifyTokenDigest = token == null || token.isBlank() ? null : sha256(token.strip());
        this.signatureVerification = properties.isSignatureVerification();
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!signatureVerification) {
            log.warn("Meta webhook '{}': signature verification is DISABLED - this is fine for dev,"
                    + " dangerous in production", id);
        }
        if (verifyTokenDigest == null) {
            log.warn("Meta webhook '{}': no verify-token configured; the subscription handshake will be refused", id);
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public WebhookResponse handle(WebhookRequest request) {
        if (!request.subPath().isEmpty()) {
            return error(404, "not found");
        }
        if (request.isMethod(WebhookRequest.GET)) {
            return handshake(request);
        }
        if (!request.isMethod(WebhookRequest.POST)) {
            return error(405, "method not allowed");
        }
        byte[] body = request.body();
        if (signatureVerification) {
            if (!signatureMatches(body, request.header(SIGNATURE_HEADER))) {
                log.warn("Meta webhook '{}': signature verification failed", id);
                return WebhookResponse.signatureRejected(WebhookResponse.APPLICATION_JSON,
                        "{\"error\":\"signature verification failed\"}");
            }
        } else {
            log.warn("Meta webhook '{}': signature verification is DISABLED - this is fine for dev,"
                    + " dangerous in production", id);
        }
        ObjectNode root = MetaJson.readObject(body).orElse(null);
        if (root == null) {
            return error(400, "body is not a JSON object");
        }
        Parsed parsed = parse(root);
        if (parsed.inbound() > 0 || parsed.skipped() > 0) {
            log.info("Meta webhook '{}': {} status event(s), {} inbound message(s) ignored, {} status(es) skipped",
                    id, parsed.events().size(), parsed.inbound(), parsed.skipped());
        }
        return WebhookResponse.json(200, "{\"" + FIELD_STATUS + "\":\"accepted\"}", parsed.events());
    }

    private WebhookResponse handshake(WebhookRequest request) {
        String token = request.queryParameter("hub.verify_token");
        String challenge = request.queryParameter("hub.challenge");
        boolean ok = MODE_SUBSCRIBE.equals(request.queryParameter("hub.mode"))
                && verifyTokenDigest != null
                && token != null
                && challenge != null
                && MessageDigest.isEqual(verifyTokenDigest, sha256(token));
        if (!ok) {
            log.warn("Meta webhook '{}': subscription handshake refused", id);
            return WebhookResponse.signatureRejected(WebhookResponse.TEXT_PLAIN, "verification failed");
        }
        log.info("Meta webhook '{}': subscription handshake accepted", id);
        return WebhookResponse.text(200, challenge);
    }

    boolean signatureMatches(byte[] body, String header) {
        if (header == null || !header.regionMatches(true, 0, SIGNATURE_PREFIX, 0, SIGNATURE_PREFIX.length())) {
            return false;
        }
        byte[] provided = header.substring(SIGNATURE_PREFIX.length()).strip().toLowerCase(Locale.ROOT)
                .getBytes(StandardCharsets.US_ASCII);
        byte[] expected = HexFormat.of().formatHex(hmac(body)).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, provided);
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(appSecret, HMAC));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private record Parsed(List<DeliveryEvent> events, int inbound, int skipped) {
    }

    private Parsed parse(ObjectNode root) {
        List<DeliveryEvent> events = new ArrayList<>();
        int inbound = 0;
        int skipped = 0;
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                inbound += value.path("messages").size();
                String phoneNumberId = MetaJson.text(value.path("metadata"), ATTR_PHONE_NUMBER_ID);
                for (JsonNode status : value.path("statuses")) {
                    DeliveryEvent event = toEvent(status, phoneNumberId);
                    if (event == null) {
                        skipped++;
                    } else {
                        events.add(event);
                    }
                }
            }
        }
        return new Parsed(events, inbound, skipped);
    }

    private DeliveryEvent toEvent(JsonNode status, String phoneNumberId) {
        String messageId = MetaJson.text(status, "id");
        String statusText = MetaJson.text(status, FIELD_STATUS);
        if (messageId == null || messageId.isBlank() || statusText == null) {
            return null;
        }
        String normalised = statusText.toLowerCase(Locale.ROOT);
        String timestampText = MetaJson.text(status, "timestamp");
        JsonNode error = status.path("errors").path(0);
        Integer errorCode = error.path("code").isInt() ? error.path("code").intValue() : null;
        String errorTitle = MetaJson.text(error, "title");

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_STATUS, normalised);
        putIfPresent(attributes, ATTR_PHONE_NUMBER_ID, phoneNumberId);
        putIfPresent(attributes, ATTR_CALLBACK_DATA, MetaJson.text(status, ATTR_CALLBACK_DATA));
        putIfPresent(attributes, ATTR_ERROR_CODE, errorCode == null ? null : errorCode.toString());
        putIfPresent(attributes, ATTR_ERROR_TITLE, errorTitle == null ? null : PiiMasking.redact(errorTitle));
        putIfPresent(attributes, ATTR_PRICING_CATEGORY, MetaJson.text(status.path("pricing"), CATEGORY));
        putIfPresent(attributes, ATTR_CONVERSATION_CATEGORY,
                MetaJson.text(status.path("conversation").path("origin"), "type"));

        DeliveryStatus mapped = mapStatus(normalised, errorCode);
        String reason = "failed".equals(normalised) ? reason(errorCode, errorTitle) : null;
        return new DeliveryEvent(timestamp(timestampText), PROVIDER_NAME, messageId,
                messageId + ":" + normalised + ":" + (timestampText == null ? "" : timestampText),
                mapped, reason, attributes);
    }

    static DeliveryStatus mapStatus(String status, Integer errorCode) {
        if (status == null) {
            return DeliveryStatus.UNKNOWN;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "sent" -> DeliveryStatus.SENT;
            case "delivered" -> DeliveryStatus.DELIVERED;
            case "read" -> DeliveryStatus.READ;
            case "failed" -> errorCode != null && errorCode == CODE_UNDELIVERABLE
                    ? DeliveryStatus.BOUNCED
                    : DeliveryStatus.FAILED_AT_PROVIDER;
            default -> DeliveryStatus.UNKNOWN;
        };
    }

    private static String reason(Integer errorCode, String errorTitle) {
        if (errorCode == null && errorTitle == null) {
            return null;
        }
        String title = errorTitle == null ? "" : PiiMasking.redact(errorTitle);
        if (errorCode == null) {
            return title;
        }
        return title.isEmpty() ? errorCode.toString() : errorCode + " " + title;
    }

    private Instant timestamp(String unixSeconds) {
        if (unixSeconds != null) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(unixSeconds.strip()));
            } catch (NumberFormatException | DateTimeException _) {
                // fall through to now
            }
        }
        return clock.instant();
    }

    private static void putIfPresent(Map<String, String> attributes, String key, String value) {
        if (value != null && !value.isBlank()) {
            attributes.put(key, value);
        }
    }

    private static WebhookResponse error(int status, String message) {
        return WebhookResponse.json(status, "{\"" + FIELD_ERROR + "\":\"" + message + "\"}");
    }
}
