package com.braseth.ecommerce.modules.staff.service;

import java.util.regex.Pattern;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Photo lists (MediaAsset[]) as the editors send them. Only plain http(s)
 * addresses and the site's own paths are kept, so nothing like
 * "javascript:" can reach an img tag.
 */
final class MediaAssets {

    private static final Pattern URL = Pattern.compile("^(https?://|/)[^\\s\"'<>\\\\]*$");

    private MediaAssets() {
    }

    static ArrayNode normalize(ObjectMapper json, JsonNode photos, String path, int max) {
        ArrayNode out = json.createArrayNode();
        if (photos == null || photos.isNull()) {
            return out;
        }
        if (!photos.isArray() || photos.size() > max) {
            throw ApiProblem.field(path, "too_many", "Add at most " + max + " photos.");
        }
        for (JsonNode photo : photos) {
            String id = LocalizedText.text(photo.get("id"));
            String url = url(photo.get("url"), path);
            if (id == null || id.length() > 100 || url == null) {
                throw ApiProblem.field(path, "invalid_photo", "Upload the photo again.");
            }
            ObjectNode clean = out.addObject().put("id", id).put("url", url);
            String placeholder = url(photo.get("placeholderUrl"), path);
            if (placeholder != null) {
                clean.put("placeholderUrl", placeholder);
            }
            String alt = LocalizedText.text(photo.get("alt"));
            if (alt != null && alt.length() > 300) {
                throw ApiProblem.field(path, "too_long", "Keep each description under 300 characters.");
            }
            clean.put("alt", alt == null ? "" : alt);
            dimension(clean, photo, "width");
            dimension(clean, photo, "height");
            String fit = LocalizedText.text(photo.get("fit"));
            if ("cover".equals(fit) || "contain".equals(fit)) {
                clean.put("fit", fit);
            }
        }
        return out;
    }

    private static String url(JsonNode node, String path) {
        String value = LocalizedText.text(node);
        if (value == null) {
            return null;
        }
        if (value.length() > 1000 || !URL.matcher(value).matches()) {
            throw ApiProblem.field(path, "invalid_photo", "Upload the photo again.");
        }
        return value;
    }

    private static void dimension(ObjectNode out, JsonNode photo, String field) {
        JsonNode value = photo.get(field);
        if (value != null && value.canConvertToInt() && value.asInt() > 0 && value.asInt() <= 20000) {
            out.put(field, value.asInt());
        }
    }
}
