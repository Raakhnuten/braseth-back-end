package com.braseth.ecommerce.common.util;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The web app's Paged&lt;T&gt;: {items, page, size, totalItems, totalPages}. */
public final class Paging {

    private Paging() {
    }

    public static ObjectNode page(ObjectMapper json, List<? extends JsonNode> items, int page, int size, long total) {
        ObjectNode out = json.createObjectNode();
        out.putArray("items").addAll(items);
        out.put("page", page);
        out.put("size", size);
        out.put("totalItems", total);
        out.put("totalPages", (int) Math.ceil(total / (double) size));
        return out;
    }
}
