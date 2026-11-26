package com.lazydevs.notification.core.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lazydevs.notification.api.model.NotificationRequest;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Fingerprint of what a notification request asks to send (DD-32): the
 * SHA-256 hex digest of a canonical JSON document.
 *
 * <p>The document holds the channel, the notification type, the explicit
 * template id, the recipient (every field except its tracking {@code id},
 * so every address list in order), the template data, the explicitly
 * requested provider and, per attachment, its file name, size and SHA-256
 * (or its URL). The request, correlation and idempotency ids, the tenant and
 * caller (already part of the idempotency key), the metadata, the priority
 * and the schedule are left out: they do not change what is sent.
 *
 * <p>Canonical form: every string and map key is Unicode NFC-normalised,
 * map keys are sorted at every depth, array order is kept, numbers are
 * written as their Java value prints them (so {@code 45} and {@code 45.0}
 * differ) and nulls are kept. Values that are not JSON types (records, beans,
 * {@code java.time} values) are first converted with Jackson, so the
 * fingerprint never depends on {@code toString()} or identity.
 *
 * <p>Thread-safe.
 */
public final class RequestFingerprint {

    private static final ObjectMapper TREE = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private RequestFingerprint() {
    }

    /**
     * Fingerprint a request.
     *
     * @param request the enriched request
     * @return 64 lower-case hex characters
     */
    public static String of(NotificationRequest request) {
        return sha256Hex(canonicalJson(document(request)).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The canonical JSON text of a value, as hashed by {@link #of}. Exposed
     * for tests and for operators comparing two requests.
     *
     * @param value maps, lists, arrays, strings, numbers, booleans, {@code null}
     *              or any value Jackson can convert to those
     * @return the canonical JSON
     */
    public static String canonicalJson(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static Map<String, Object> document(NotificationRequest request) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("channel", request.getChannel() == null ? null : request.getChannel().name());
        doc.put("notificationType", request.getNotificationType());
        doc.put("templateId", request.getTemplateId());
        doc.put("recipient", recipient(request));
        doc.put("templateData", request.getTemplateData());
        doc.put("provider", request.getProvider());
        doc.put("attachments", attachments(request.getAttachments()));
        return doc;
    }

    private static Object recipient(NotificationRequest request) {
        if (request.getRecipient() == null) {
            return null;
        }
        Map<?, ?> tree = TREE.convertValue(request.getRecipient(), Map.class);
        Map<Object, Object> copy = new LinkedHashMap<>(tree);
        copy.remove("id");
        return copy;
    }

    private static List<Object> attachments(List<NotificationRequest.Attachment> attachments) {
        if (attachments == null) {
            return null;
        }
        List<Object> out = new ArrayList<>(attachments.size());
        for (NotificationRequest.Attachment attachment : attachments) {
            out.add(attachment == null ? null : attachment(attachment));
        }
        return out;
    }

    private static Map<String, Object> attachment(NotificationRequest.Attachment attachment) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filename", attachment.filename());
        byte[] content = attachment.content();
        out.put("size", content == null ? null : content.length);
        out.put("sha256", content == null ? null : sha256Hex(content));
        out.put("url", attachment.url());
        return out;
    }

    private static void write(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case CharSequence s -> string(out, s.toString());
            case Boolean b -> out.append(b);
            case Number n -> out.append(n);
            case Character c -> string(out, c.toString());
            case Enum<?> e -> string(out, e.name());
            case Map<?, ?> m -> map(out, m);
            case Collection<?> c -> array(out, c);
            case Object a when a.getClass().isArray() -> array(out, arrayToList(a));
            default -> write(out, TREE.convertValue(value, Object.class));
        }
    }

    private static void map(StringBuilder out, Map<?, ?> map) {
        Map<String, Object> sorted = new TreeMap<>();
        map.forEach((k, v) -> sorted.put(nfc(String.valueOf(k)), v));
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            string(out, entry.getKey());
            out.append(':');
            write(out, entry.getValue());
        }
        out.append('}');
    }

    private static void array(StringBuilder out, Collection<?> values) {
        out.append('[');
        boolean first = true;
        for (Object v : values) {
            if (!first) {
                out.append(',');
            }
            first = false;
            write(out, v);
        }
        out.append(']');
    }

    private static List<Object> arrayToList(Object array) {
        int length = Array.getLength(array);
        List<Object> out = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            out.add(Array.get(array, i));
        }
        return out;
    }

    private static void string(StringBuilder out, String value) {
        String s = nfc(value);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static String nfc(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // Every JDK must provide SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
