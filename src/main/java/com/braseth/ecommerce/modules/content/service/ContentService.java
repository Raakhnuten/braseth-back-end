package com.braseth.ecommerce.modules.content.service;

import static com.braseth.ecommerce.common.util.Strings.blankToNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.common.util.JsonNodes;
import com.braseth.ecommerce.common.util.Paging;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.r2dbc.spi.Readable;
import reactor.core.publisher.Mono;

/**
 * The public reads of the site's own content: settings, services, portfolio.
 *
 * Text is stored per language ({"en": {...}, "km": {...}}). A Khmer reader
 * gets each Khmer field that has been written and the English one where it
 * has not, field by field — so a half-translated service still reads whole.
 * Only published, undeleted rows are ever returned, and a portfolio client is
 * named only when BRASETH holds the client's permission.
 */
@Service
public class ContentService {

    private static final int MAX_PAGE_SIZE = 48;

    private final DatabaseClient db;
    private final ObjectMapper json;

    ContentService(DatabaseClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    // ---- Site settings ------------------------------------------------------

    public Mono<ObjectNode> siteConfig(String locale) {
        return db.sql("SELECT * FROM site_settings WHERE id = 1")
                .map(row -> {
                    JsonNode text = localized(JsonNodes.column(json, row, "site_text"), locale);
                    JsonNode about = text.path("about");
                    ObjectNode out = json.createObjectNode();
                    out.put("companyName", row.get("company_name", String.class));
                    out.put("legalName", row.get("legal_name", String.class));
                    out.set("tagline", orNull(text.get("tagline")));
                    out.set("address", JsonNodes.column(json, row, "address"));
                    out.set("phones", JsonNodes.column(json, row, "phones"));
                    out.put("email", row.get("email", String.class));
                    out.put("telegram", row.get("telegram", String.class));
                    out.set("social", JsonNodes.column(json, row, "social"));
                    out.set("businessHours", JsonNodes.column(json, row, "business_hours"));
                    out.put("mapEmbedUrl", row.get("map_embed_url", String.class));
                    out.set("trustStats", JsonNodes.column(json, row, "trust_stats"));
                    out.set("clientLogos", JsonNodes.column(json, row, "client_logos"));
                    out.set("reviews", JsonNodes.column(json, row, "reviews"));
                    out.put("reviewsUrl", row.get("reviews_url", String.class));
                    ObjectNode aboutOut = out.putObject("about");
                    for (String field : List.of("intro", "story", "mission", "vision", "capability", "quality")) {
                        aboutOut.set(field, orNull(about.get(field)));
                    }
                    return out;
                })
                .one();
    }

    // ---- Services -----------------------------------------------------------

    public Mono<ArrayNode> services(String locale) {
        return db.sql("""
                SELECT * FROM services
                WHERE status = 'published' AND deleted_at IS NULL
                ORDER BY position, created_at
                """)
                .map(row -> serviceSummary(row, locale))
                .all()
                .collect(json::createArrayNode, ArrayNode::add);
    }

    public Mono<ObjectNode> service(String slug, String locale) {
        return db.sql("SELECT * FROM services WHERE slug = :slug AND status = 'published' AND deleted_at IS NULL")
                .bind("slug", slug)
                .map(row -> {
                    ObjectNode out = serviceSummary(row, locale);
                    JsonNode text = localized(JsonNodes.column(json, row, "service_text"), locale);
                    out.put("description", text.path("description").asText(""));
                    out.set("benefits", arrayOr(text.get("benefits")));
                    out.set("useCases", arrayOr(text.get("useCases")));
                    ArrayNode process = out.putArray("process");
                    int order = 1;
                    for (JsonNode step : JsonNodes.column(json, row, "process")) {
                        // A step's Khmer is used only when its title is written.
                        JsonNode words = hasText(step.path(locale).get("title")) ? step.path(locale) : step.path("en");
                        process.addObject()
                                .put("order", order++)
                                .put("title", words.path("title").asText(""))
                                .put("description", words.path("description").asText(""));
                    }
                    ArrayNode photos = (ArrayNode) JsonNodes.column(json, row, "photos");
                    ArrayNode gallery = out.putArray("gallery");
                    for (int i = 1; i < photos.size(); i++) {
                        gallery.add(photos.get(i));
                    }
                    out.set("applicableCategorySlugs", JsonNodes.column(json, row, "applicable_category_slugs"));
                    Integer moq = row.get("minimum_order_quantity", Integer.class);
                    if (moq == null) {
                        out.putNull("minimumOrderQuantity");
                    } else {
                        out.put("minimumOrderQuantity", moq);
                    }
                    out.set("turnaround", orNull(text.get("turnaround")));
                    return out;
                })
                .one()
                .switchIfEmpty(Mono.error(() -> new ApiProblem(HttpStatus.NOT_FOUND, "not_found",
                        "We could not find that service.")));
    }

    private ObjectNode serviceSummary(Readable row, String locale) {
        JsonNode text = localized(JsonNodes.column(json, row, "service_text"), locale);
        JsonNode photos = JsonNodes.column(json, row, "photos");
        ObjectNode out = json.createObjectNode();
        out.put("id", row.get("id", UUID.class).toString());
        out.put("slug", row.get("slug", String.class));
        out.put("name", text.path("name").asText(""));
        out.put("shortDescription", text.path("shortDescription").asText(""));
        out.set("image", photos.size() > 0 ? photos.get(0) : NullNode.getInstance());
        out.put("method", row.get("method", String.class));
        return out;
    }

    // ---- Portfolio ----------------------------------------------------------

    public Mono<ObjectNode> portfolio(String category, String search, int page, int size, String locale) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        GenericExecuteSpec spec = db.sql("""
                SELECT *, count(*) OVER () AS total FROM portfolio_projects
                WHERE status = 'published' AND deleted_at IS NULL
                  AND (CAST(:category AS text) IS NULL OR category = :category)
                  AND (CAST(:search AS text) IS NULL
                       OR strpos(lower(project_text #>> '{en,title}'), lower(:search)) > 0
                       OR strpos(lower(coalesce(project_text #>> '{km,title}', '')), lower(:search)) > 0)
                ORDER BY completed_on DESC NULLS LAST, created_at DESC
                LIMIT :limit OFFSET :offset
                """);
        spec = bindText(spec, "category", blankToNull(category));
        spec = bindText(spec, "search", blankToNull(search));
        return spec
                .bind("limit", safeSize)
                .bind("offset", (safePage - 1) * safeSize)
                .map(row -> new Object[] { portfolioSummary(row, locale), row.get("total", Long.class) })
                .all()
                .collectList()
                .map(rows -> {
                    long total = rows.isEmpty() ? 0 : (Long) rows.get(0)[1];
                    List<ObjectNode> items = rows.stream().map(r -> (ObjectNode) r[0]).toList();
                    return Paging.page(json, items, safePage, safeSize, total);
                });
    }

    public Mono<ObjectNode> project(String slug, String locale) {
        return db.sql("""
                SELECT * FROM portfolio_projects
                WHERE slug = :slug AND status = 'published' AND deleted_at IS NULL
                """)
                .bind("slug", slug)
                .map(row -> {
                    ObjectNode out = portfolioSummary(row, locale);
                    JsonNode text = localized(JsonNodes.column(json, row, "project_text"), locale);
                    out.put("summary", text.path("summary").asText(""));
                    out.set("gallery", JsonNodes.column(json, row, "photos"));
                    String client = row.get("client_name", String.class);
                    // Without the client's written permission the name never leaves the staff side.
                    if (Boolean.TRUE.equals(row.get("client_name_permitted", Boolean.class)) && hasText(client)) {
                        out.put("clientName", client);
                    }
                    if (hasText(text.get("productType"))) {
                        out.put("productType", text.get("productType").asText());
                    }
                    if (hasText(text.get("printMethod"))) {
                        out.put("printMethod", text.get("printMethod").asText());
                    }
                    out.putArray("relatedProductSlugs");
                    return out;
                })
                .one()
                .switchIfEmpty(Mono.error(() -> new ApiProblem(HttpStatus.NOT_FOUND, "not_found",
                        "We could not find that project.")));
    }

    private ObjectNode portfolioSummary(Readable row, String locale) {
        JsonNode text = localized(JsonNodes.column(json, row, "project_text"), locale);
        JsonNode photos = JsonNodes.column(json, row, "photos");
        ObjectNode out = json.createObjectNode();
        out.put("id", row.get("id", UUID.class).toString());
        out.put("slug", row.get("slug", String.class));
        out.put("title", text.path("title").asText(""));
        out.put("category", row.get("category", String.class));
        out.set("cover", photos.size() > 0 ? photos.get(0) : NullNode.getInstance());
        LocalDate completed = row.get("completed_on", LocalDate.class);
        if (completed != null) {
            out.put("completedOn", completed.toString());
        }
        return out;
    }

    // ---- Helpers ------------------------------------------------------------

    /**
     * The text for {@code locale}: English as the base, with every Khmer field
     * that has been written laid over it. A blank string or an empty list
     * counts as not written.
     */
    private JsonNode localized(JsonNode all, String locale) {
        ObjectNode merged = all.path("en").isObject() ? ((ObjectNode) all.get("en")).deepCopy() : json.createObjectNode();
        if (!"en".equals(locale) && all.path(locale).isObject()) {
            overlay(merged, all.get(locale));
        }
        return merged;
    }

    private static void overlay(ObjectNode base, JsonNode over) {
        over.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value.isObject() && base.path(entry.getKey()).isObject()) {
                overlay((ObjectNode) base.get(entry.getKey()), value);
            } else if (hasText(value) || (value.isArray() && !value.isEmpty())) {
                base.set(entry.getKey(), value);
            }
        });
    }

    private static boolean hasText(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static JsonNode orNull(JsonNode node) {
        return hasText(node) ? node : NullNode.getInstance();
    }

    private ArrayNode arrayOr(JsonNode node) {
        return node != null && node.isArray() ? (ArrayNode) node : json.createArrayNode();
    }

    private static GenericExecuteSpec bindText(GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

}
