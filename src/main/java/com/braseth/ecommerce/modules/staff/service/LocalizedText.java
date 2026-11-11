package com.braseth.ecommerce.modules.staff.service;

import java.util.List;
import java.util.regex.Pattern;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Words staff write in both languages, as the editors send them:
 * {"en": {...}, "km": {...}}. English carries the required fields; Khmer may
 * be left empty, field by field, and the site shows the English instead.
 * Blank text is stored as null and blank list entries are dropped, so
 * "missing" has one meaning.
 */
final class LocalizedText {

    static final List<String> LOCALES = List.of("en", "km");

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private LocalizedText() {
    }

    enum Kind { TEXT, LIST }

    /**
     * One field of the text. {@code mandatory} applies to English only.
     * A non-null field is sent as "" rather than null when empty, as the
     * editors' types expect.
     */
    record Field(String name, Kind kind, int max, boolean mandatory, boolean nonNull) {

        /** Must be written in English (a name or title). */
        static Field required(String name, int max) {
            return new Field(name, Kind.TEXT, max, true, true);
        }

        /** May be empty, but is a string, never null. */
        static Field text(String name, int max) {
            return new Field(name, Kind.TEXT, max, false, true);
        }

        static Field optional(String name, int max) {
            return new Field(name, Kind.TEXT, max, false, false);
        }

        static Field list(String name, int max) {
            return new Field(name, Kind.LIST, max, false, false);
        }
    }

    /** Both languages, cleaned and checked; errors name the field as the form does, e.g. "text.en.name". */
    static ObjectNode normalize(ObjectMapper json, JsonNode text, List<Field> fields, String path) {
        if (text == null || !text.isObject()) {
            throw ApiProblem.field(path, "required", "Write the English text.");
        }
        ObjectNode out = json.createObjectNode();
        for (String locale : LOCALES) {
            JsonNode words = text.path(locale);
            ObjectNode clean = out.putObject(locale);
            for (Field field : fields) {
                String at = path + "." + locale + "." + field.name();
                JsonNode value = words.get(field.name());
                if (field.kind() == Kind.LIST) {
                    clean.set(field.name(), list(json, value, field.max(), at));
                    continue;
                }
                String cleaned = text(value);
                if (cleaned != null && cleaned.length() > field.max()) {
                    throw ApiProblem.field(at, "too_long", "Keep this under " + field.max() + " characters.");
                }
                if (cleaned == null && field.mandatory() && locale.equals("en")) {
                    throw ApiProblem.field(at, "required", "This is required in English.");
                }
                if (cleaned == null) {
                    if (field.nonNull()) {
                        clean.put(field.name(), "");
                    } else {
                        clean.putNull(field.name());
                    }
                } else {
                    clean.put(field.name(), cleaned);
                }
            }
        }
        return out;
    }

    /** True when every field written in English is written in Khmer too. */
    static boolean khmerComplete(JsonNode text, List<Field> fields) {
        JsonNode en = text.path("en");
        JsonNode km = text.path("km");
        for (Field field : fields) {
            if (filled(en.get(field.name())) && !filled(km.get(field.name()))) {
                return false;
            }
        }
        return true;
    }

    /** Stored text read back for an editor: both languages, every field present. */
    static ObjectNode read(ObjectMapper json, JsonNode stored, List<Field> fields) {
        ObjectNode out = json.createObjectNode();
        for (String locale : LOCALES) {
            JsonNode words = stored == null ? null : stored.path(locale);
            ObjectNode clean = out.putObject(locale);
            for (Field field : fields) {
                JsonNode value = words == null ? null : words.get(field.name());
                if (field.kind() == Kind.LIST) {
                    clean.set(field.name(), value != null && value.isArray() ? value : json.createArrayNode());
                } else if (value == null || value.isNull()) {
                    if (field.nonNull()) {
                        clean.put(field.name(), "");
                    } else {
                        clean.putNull(field.name());
                    }
                } else {
                    clean.set(field.name(), value);
                }
            }
        }
        return out;
    }

    static String slug(String value) {
        String slug = value == null ? "" : value.trim();
        if (!SLUG.matcher(slug).matches() || slug.length() > 180) {
            throw ApiProblem.field("slug", "invalid_slug",
                    "Use lowercase letters, numbers and single hyphens, e.g. cotton-tee.");
        }
        return slug;
    }

    static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static ArrayNode list(ObjectMapper json, JsonNode value, int max, String at) {
        ArrayNode out = json.createArrayNode();
        if (value == null || value.isNull()) {
            return out;
        }
        if (!value.isArray() || value.size() > 30) {
            throw ApiProblem.field(at, "invalid", "Keep the list to 30 entries.");
        }
        for (JsonNode entry : value) {
            String cleaned = text(entry);
            if (cleaned != null) {
                if (cleaned.length() > max) {
                    throw ApiProblem.field(at, "too_long", "Keep each entry under " + max + " characters.");
                }
                out.add(cleaned);
            }
        }
        return out;
    }

    private static boolean filled(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isArray()) {
            return node.size() > 0;
        }
        return !node.asText().isBlank();
    }
}
