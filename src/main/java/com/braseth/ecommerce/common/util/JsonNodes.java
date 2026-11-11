package com.braseth.ecommerce.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Readable;

/** Reading jsonb columns and JSON text into Jackson trees. */
public final class JsonNodes {

    private JsonNodes() {
    }

    /** A jsonb column as a tree; SQL NULL becomes {@link NullNode}. Unreadable JSON is a bug, so it throws. */
    public static JsonNode column(ObjectMapper json, Readable row, String column) {
        Json value = row.get(column, Json.class);
        return value == null ? NullNode.getInstance() : parse(json, value.asString(), column);
    }

    /** JSON text as a tree; throws {@link IllegalStateException} when it is not JSON. */
    public static JsonNode parse(ObjectMapper json, String text) {
        return parse(json, text, "text");
    }

    /** JSON text as a tree, or {@link NullNode} when it is missing or not JSON. */
    public static JsonNode parseOrNull(ObjectMapper json, String text) {
        if (text == null) {
            return NullNode.getInstance();
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            return NullNode.getInstance();
        }
    }

    private static JsonNode parse(ObjectMapper json, String text, String source) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable JSON in " + source, e);
        }
    }
}
