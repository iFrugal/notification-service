package com.lazydevs.notification.channel.push.fcm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * JSON and hashing helpers shared by the module. Jackson 2 tree model only, so
 * no class of this module is bound reflectively (nothing to register for a
 * native image).
 */
final class FcmJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** Prefix of every target hash, so a reader knows how it was made. */
    static final String HASH_PREFIX = "sha256:";

    /** Hex characters of the SHA-256 digest kept in a target hash (64 bits). */
    private static final int TARGET_HASH_HEX_LENGTH = 16;

    private FcmJson() {
    }

    static byte[] write(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
            // A tree of plain nodes always serializes.
            throw new IllegalStateException("cannot serialize JSON", e);
        }
    }

    static String writeString(JsonNode node) {
        return new String(write(node), StandardCharsets.UTF_8);
    }

    /** @return the parsed object, or empty when the bytes are not a JSON object */
    static Optional<ObjectNode> readObject(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(bytes);
            return node instanceof ObjectNode object ? Optional.of(object) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** @return the text of a string field, or {@code null} when absent, not a string or blank */
    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    /**
     * Copy every field of {@code source} into {@code target}; nested objects are
     * merged recursively, everything else replaces the target's value.
     */
    static void deepMerge(ObjectNode target, ObjectNode source) {
        Iterator<Map.Entry<String, JsonNode>> fields = source.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode existing = target.get(field.getKey());
            if (existing instanceof ObjectNode existingObject && field.getValue() instanceof ObjectNode incoming) {
                deepMerge(existingObject, incoming);
            } else {
                target.set(field.getKey(), field.getValue().deepCopy());
            }
        }
    }

    /** @return the lowercase hex SHA-256 of the UTF-8 bytes of {@code value} */
    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every JDK must provide SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * The only form in which a device token or installation id appears in logs,
     * results and events: {@code sha256:} and the first 16 hex characters of its
     * SHA-256. Stable across JVMs, so an application can hash its stored tokens
     * the same way to find the one to delete.
     */
    static String targetHash(String target) {
        return HASH_PREFIX + sha256Hex(target).substring(0, TARGET_HASH_HEX_LENGTH);
    }
}
