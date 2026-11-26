package com.lazydevs.notification.channel.whatsapp.meta;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Jackson tree-model helpers: the module builds and reads JSON without POJO
 * reflection, so a native image needs no hints for it.
 */
final class MetaJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private MetaJson() {
    }

    /** @return the body as a JSON object, empty when it is empty, not JSON or not an object */
    static Optional<ObjectNode> readObject(byte[] body) {
        if (body == null || body.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            return node instanceof ObjectNode object ? Optional.of(object) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static byte[] writeBytes(JsonNode node) {
        return writeString(node).getBytes(StandardCharsets.UTF_8);
    }

    static String writeString(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise a JSON tree", e);
        }
    }

    /** @return the text of a field, or {@code null} when it is absent, null or not a scalar */
    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.isContainerNode() ? null : value.asText();
    }
}
