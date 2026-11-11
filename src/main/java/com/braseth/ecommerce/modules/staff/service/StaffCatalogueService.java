package com.braseth.ecommerce.modules.staff.service;

import static com.braseth.ecommerce.modules.staff.service.LocalizedText.Field.optional;
import static com.braseth.ecommerce.modules.staff.service.LocalizedText.Field.required;
import static com.braseth.ecommerce.modules.staff.service.LocalizedText.Field.text;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.common.util.JsonNodes;
import com.braseth.ecommerce.common.util.Paging;
import com.braseth.ecommerce.modules.staff.service.LocalizedText.Field;
import com.braseth.ecommerce.modules.staff.service.StaffGate.Staff;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Readable;
import reactor.core.publisher.Mono;

/**
 * Products and categories, as staff edit them (AdminProduct, AdminCategory):
 *
 * <ul>
 * <li>English text in the name / description columns, both languages in
 * {@code web_text};</li>
 * <li>photos, colours and sizes as the product's {@code photos},
 * {@code colors} and {@code sizes} lists — never priced here (BRASETH prices
 * per quote);</li>
 * <li>print methods and positions as the product's {@code print_methods} and
 * {@code print_positions}, in the web app's own names.</li>
 * </ul>
 *
 * Draft means not active: hidden from the public site.
 */
@Service
public class StaffCatalogueService {

    static final List<Field> PRODUCT_TEXT = List.of(
            required("name", 200), text("shortDescription", 500), text("description", 10000),
            optional("material", 200), optional("careInstructions", 2000), optional("productionLeadTime", 200));

    private static final List<Field> CATEGORY_TEXT = List.of(required("name", 150), optional("description", 2000));

    /** The print methods the web app offers. */
    private static final Set<String> METHODS = Set.of(
            "silkscreen", "embroidery", "heat-transfer", "sublimation", "dtf", "pad-printing");

    private static final Set<String> POSITIONS = Set.of("front", "back", "left-sleeve", "right-sleeve", "pocket", "other");

    private final DatabaseClient db;
    private final ObjectMapper json;
    private final StaffRecords records;
    private final StaffMediaService media;
    private final TransactionalOperator transaction;

    StaffCatalogueService(DatabaseClient db, ObjectMapper json, StaffRecords records, StaffMediaService media,
            ReactiveTransactionManager transactionManager) {
        this.db = db;
        this.json = json;
        this.records = records;
        this.media = media;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    // ---- Categories ---------------------------------------------------------

    private static final String CATEGORY_SELECT = """
            SELECT c.*, (SELECT count(*) FROM products p WHERE p.category_id = c.id AND p.deleted_at IS NULL) AS product_count
            FROM categories c WHERE c.deleted_at IS NULL
            """;

    public Mono<ArrayNode> categories() {
        return db.sql(CATEGORY_SELECT + " ORDER BY c.sort_order, c.name")
                .map(this::category)
                .all()
                .collect(json::createArrayNode, ArrayNode::add);
    }

    public Mono<ObjectNode> createCategory(Staff actor, JsonNode body) {
        CategoryRow input;
        try {
            input = categoryInput(body);
        } catch (ApiProblem problem) {
            return Mono.error(problem);
        }
        return validParent(null, input.parentId())
                .then(bindCategory(db.sql("""
                        INSERT INTO categories (parent_id, name, slug, description, sort_order, web_text, created_by, updated_by)
                        VALUES (:parent, :name, :slug, :description, :position, :text, :actor, :actor)
                        RETURNING id
                        """), input, actor)
                        .map(row -> row.get("id", UUID.class))
                        .one())
                .flatMap(id -> records.activity(actor, "category", id, input.name(), "Created").thenReturn(id))
                .as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> StaffContentService.slugTaken())
                .flatMap(this::categoryById);
    }

    public Mono<ObjectNode> updateCategory(Staff actor, UUID id, JsonNode body) {
        CategoryRow input;
        try {
            input = categoryInput(body);
        } catch (ApiProblem problem) {
            return Mono.error(problem);
        }
        return validParent(id, input.parentId())
                .then(bindCategory(db.sql("""
                        UPDATE categories SET parent_id = :parent, name = :name, slug = :slug, description = :description,
                               sort_order = :position, web_text = :text, updated_by = :actor
                        WHERE id = :id AND deleted_at IS NULL
                        """), input, actor)
                        .bind("id", id)
                        .fetch()
                        .rowsUpdated())
                .flatMap(updated -> updated == 0 ? Mono.error(StaffViews.notFound("category"))
                        : records.activity(actor, "category", id, input.name(), "Edited"))
                .as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> StaffContentService.slugTaken())
                .then(Mono.defer(() -> categoryById(id)));
    }

    /**
     * Only an empty category can go. Its address is freed for reuse, since
     * the unique index on slug also covers removed rows.
     */
    public Mono<Void> deleteCategory(Staff actor, UUID id) {
        return db.sql("""
                SELECT c.name,
                       EXISTS (SELECT 1 FROM products p WHERE p.category_id = c.id AND p.deleted_at IS NULL) AS has_products,
                       EXISTS (SELECT 1 FROM categories k WHERE k.parent_id = c.id AND k.deleted_at IS NULL) AS has_children
                FROM categories c WHERE c.id = :id AND c.deleted_at IS NULL FOR UPDATE
                """)
                .bind("id", id)
                .map(row -> new Object[] { row.get("name", String.class), row.get("has_products", Boolean.class),
                        row.get("has_children", Boolean.class) })
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("category")))
                .flatMap(found -> {
                    if (Boolean.TRUE.equals(found[1]) || Boolean.TRUE.equals(found[2])) {
                        return Mono.error(new ApiProblem(HttpStatus.CONFLICT, "category_not_empty",
                                Boolean.TRUE.equals(found[1]) ? "Move or remove its products first."
                                        : "Move or remove its sub-categories first."));
                    }
                    return db.sql("""
                            UPDATE categories SET deleted_at = now(), is_active = false, updated_by = :actor,
                                   slug = left(slug, 160) || '--removed-' || left(id::text, 8)
                            WHERE id = :id
                            """)
                            .bind("actor", actor.id())
                            .bind("id", id)
                            .then()
                            .then(records.activity(actor, "category", id, (String) found[0], "Removed"));
                })
                .as(transaction::transactional);
    }

    private Mono<ObjectNode> categoryById(UUID id) {
        return db.sql(CATEGORY_SELECT + " AND c.id = :id")
                .bind("id", id)
                .map(this::category)
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("category")));
    }

    /** The parent must exist, and must not be the category itself or one inside it. */
    private Mono<Void> validParent(UUID id, UUID parentId) {
        if (parentId == null) {
            return Mono.empty();
        }
        if (parentId.equals(id)) {
            return Mono.error(ApiProblem.field("parentId", "invalid_parent", "A category cannot sit inside itself."));
        }
        return db.sql("""
                WITH RECURSIVE up AS (
                    SELECT id, parent_id FROM categories WHERE id = :parent AND deleted_at IS NULL
                    UNION ALL
                    SELECT c.id, c.parent_id FROM categories c JOIN up ON c.id = up.parent_id
                )
                SELECT count(*) AS found, bool_or(id = CAST(:id AS uuid)) AS loops FROM up
                """)
                .bind("parent", parentId)
                .bind("id", id == null ? new UUID(0, 0) : id)
                .map(row -> new long[] { row.get("found", Long.class), Boolean.TRUE.equals(row.get("loops", Boolean.class)) ? 1 : 0 })
                .one()
                .flatMap(result -> result[0] == 0
                        ? Mono.error(ApiProblem.field("parentId", "invalid_parent", "Choose an existing category."))
                        : result[1] == 1
                                ? Mono.error(ApiProblem.field("parentId", "invalid_parent",
                                        "A category cannot sit inside one of its own sub-categories."))
                                : Mono.empty());
    }

    private record CategoryRow(String slug, UUID parentId, int position, ObjectNode text) {

        String name() {
            return text.path("en").path("name").asText();
        }
    }

    private CategoryRow categoryInput(JsonNode body) {
        StaffContentService.requireObject(body);
        String parent = LocalizedText.text(body.get("parentId"));
        JsonNode position = body.get("position");
        if (position != null && !position.isNull() && (!position.canConvertToInt() || Math.abs(position.asInt()) > 100_000)) {
            throw ApiProblem.field("position", "invalid_number", "Enter a whole number.");
        }
        return new CategoryRow(
                LocalizedText.slug(LocalizedText.text(body.get("slug"))),
                parent == null ? null : uuidField(parent, "parentId", "Choose an existing category."),
                position == null || position.isNull() ? 0 : position.asInt(),
                LocalizedText.normalize(json, body.get("text"), CATEGORY_TEXT, "text"));
    }

    private GenericExecuteSpec bindCategory(GenericExecuteSpec spec, CategoryRow input, Staff actor) {
        spec = spec.bind("name", input.name())
                .bind("slug", input.slug())
                .bind("position", input.position())
                .bind("text", Json.of(input.text().toString()))
                .bind("actor", actor.id());
        spec = StaffViews.bind(spec, "parent", input.parentId(), UUID.class);
        return StaffViews.bind(spec, "description", LocalizedText.text(input.text().path("en").get("description")), String.class);
    }

    /** AdminCategory. A category never saved from the staff screens reads its English from the name / description columns. */
    private ObjectNode category(Readable row) {
        ObjectNode out = json.createObjectNode();
        out.put("id", row.get("id", UUID.class).toString());
        out.put("slug", row.get("slug", String.class));
        StaffViews.id(out, "parentId", row.get("parent_id", UUID.class));
        out.put("position", row.get("sort_order", Integer.class));
        JsonNode stored = JsonNodes.column(json, row, "web_text");
        if (stored.isNull()) {
            ObjectNode en = json.createObjectNode();
            en.put("name", row.get("name", String.class));
            en.put("description", row.get("description", String.class));
            stored = json.createObjectNode().set("en", en);
        }
        out.set("text", LocalizedText.read(json, stored, CATEGORY_TEXT));
        out.put("productCount", row.get("product_count", Long.class));
        return out;
    }

    // ---- Products: reading --------------------------------------------------

    private static final String PRODUCT_SELECT = """
            SELECT p.*, c.name AS category_name, u.name AS updated_by_name
            FROM products p
            LEFT JOIN categories c ON c.id = p.category_id
            LEFT JOIN users u ON u.id = p.updated_by
            """;

    public Mono<ObjectNode> products(String categoryId, String status, String q, int page, int size) {
        int safePage = StaffViews.page(page);
        int safeSize = StaffViews.size(size);
        UUID category = categoryId == null || categoryId.isBlank() ? null : StaffViews.uuid(categoryId, "category");
        String where = """
                WHERE p.deleted_at IS NULL
                  AND (CAST(:category AS uuid) IS NULL OR p.category_id = :category)
                  AND (CAST(:status AS text) IS NULL OR (:status = 'published') = p.is_active)
                  AND (CAST(:q AS text) IS NULL
                       OR strpos(lower(p.name), lower(:q)) > 0
                       OR strpos(lower(coalesce(p.web_text #>> '{km,name}', '')), lower(:q)) > 0
                       OR strpos(p.slug, lower(:q)) > 0)
                """;
        GenericExecuteSpec rows = productFilters(db.sql(PRODUCT_SELECT + where
                + " ORDER BY coalesce(p.updated_at, p.created_at) DESC, p.id LIMIT :limit OFFSET :offset"), category, status, q)
                .bind("limit", safeSize)
                .bind("offset", (safePage - 1) * safeSize);
        GenericExecuteSpec count = productFilters(db.sql("SELECT count(*) AS n FROM products p " + where), category, status, q);
        return Mono.zip(rows.map(this::productSummary).all().collectList(), count.map(row -> row.get("n", Long.class)).one())
                .map(parts -> Paging.page(json, parts.getT1(), safePage, safeSize, parts.getT2()));
    }

    public Mono<ObjectNode> product(UUID id) {
        return db.sql(PRODUCT_SELECT + " WHERE p.id = :id AND p.deleted_at IS NULL")
                .bind("id", id)
                .map(row -> {
                    ObjectNode out = json.createObjectNode();
                    out.put("id", row.get("id", UUID.class).toString());
                    out.put("slug", row.get("slug", String.class));
                    UUID category = row.get("category_id", UUID.class);
                    out.put("categoryId", category == null ? "" : category.toString());
                    out.put("status", Boolean.TRUE.equals(row.get("is_active", Boolean.class)) ? "published" : "draft");
                    out.put("featured", Boolean.TRUE.equals(row.get("featured", Boolean.class)));
                    out.put("bestSeller", Boolean.TRUE.equals(row.get("best_seller", Boolean.class)));
                    JsonNode stored = JsonNodes.column(json, row, "web_text");
                    if (stored.isNull()) {
                        // Never saved from the staff screens: start from the English columns.
                        ObjectNode en = json.createObjectNode();
                        en.put("name", row.get("name", String.class));
                        en.put("shortDescription", row.get("short_description", String.class));
                        en.put("description", row.get("description", String.class));
                        stored = json.createObjectNode().set("en", en);
                    }
                    out.set("text", LocalizedText.read(json, stored, PRODUCT_TEXT));
                    integer(out, "minimumOrderQuantity", row.get("minimum_order_quantity", Integer.class));
                    integer(out, "maximumPrintColors", row.get("maximum_print_colors", Integer.class));
                    integer(out, "weightGsm", row.get("weight_gsm", Integer.class));
                    StaffViews.instant(out, "updatedAt", row.get("updated_at", OffsetDateTime.class));
                    StaffViews.text(out, "updatedBy", row.get("updated_by_name", String.class));

                    ArrayNode gallery = out.putArray("gallery");
                    for (JsonNode photo : JsonNodes.column(json, row, "photos")) {
                        gallery.addObject()
                                .put("id", photo.path("id").asText())
                                .put("url", photo.path("url").asText())
                                .put("alt", photo.path("alt").asText(""));
                    }
                    ArrayNode colors = out.putArray("colors");
                    for (JsonNode color : JsonNodes.column(json, row, "colors")) {
                        colors.addObject()
                                .put("id", color.path("id").asText())
                                .put("name", color.path("name").asText())
                                .put("hex", color.path("hex").asText());
                    }
                    ArrayNode sizes = out.putArray("sizes");
                    for (JsonNode entry : JsonNodes.column(json, row, "sizes")) {
                        ObjectNode size = sizes.addObject()
                                .put("id", entry.path("id").asText())
                                .put("label", entry.path("label").asText());
                        StaffViews.text(size, "measurements", entry.path("measurements").asText(null));
                    }
                    ArrayNode methods = out.putArray("printMethods");
                    texts(row.get("print_methods", String[].class)).forEach(methods::add);
                    ArrayNode positions = out.putArray("printPositions");
                    texts(row.get("print_positions", String[].class)).forEach(positions::add);
                    return out;
                })
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("product")));
    }

    private ObjectNode productSummary(Readable row) {
        ObjectNode out = json.createObjectNode();
        out.put("id", row.get("id", UUID.class).toString());
        out.put("slug", row.get("slug", String.class));
        String name = row.get("name", String.class);
        out.put("name", name);
        UUID category = row.get("category_id", UUID.class);
        out.put("categoryId", category == null ? "" : category.toString());
        String categoryName = row.get("category_name", String.class);
        out.put("categoryName", categoryName == null ? "" : categoryName);
        JsonNode photos = JsonNodes.column(json, row, "photos");
        if (photos.isEmpty()) {
            out.putNull("thumbnail");
        } else {
            JsonNode first = photos.get(0);
            String alt = first.path("alt").asText("");
            out.putObject("thumbnail")
                    .put("id", first.path("id").asText())
                    .put("url", first.path("url").asText())
                    .put("alt", alt.isBlank() ? name : alt);
        }
        out.put("status", Boolean.TRUE.equals(row.get("is_active", Boolean.class)) ? "published" : "draft");
        out.put("featured", Boolean.TRUE.equals(row.get("featured", Boolean.class)));
        out.put("bestSeller", Boolean.TRUE.equals(row.get("best_seller", Boolean.class)));
        JsonNode text = JsonNodes.column(json, row, "web_text");
        out.put("khmerComplete", !text.isNull() && LocalizedText.khmerComplete(text, PRODUCT_TEXT));
        OffsetDateTime updated = row.get("updated_at", OffsetDateTime.class);
        StaffViews.instant(out, "updatedAt", updated == null ? row.get("created_at", OffsetDateTime.class) : updated);
        return out;
    }

    private static GenericExecuteSpec productFilters(GenericExecuteSpec spec, UUID category, String status, String q) {
        spec = StaffViews.bind(spec, "category", category, UUID.class);
        spec = StaffViews.bind(spec, "status", status, String.class);
        return StaffViews.bind(spec, "q", q, String.class);
    }

    private static List<String> texts(String[] values) {
        return values == null ? List.of() : List.of(values);
    }

    // ---- Products: saving ---------------------------------------------------

    private record ProductRow(String slug, UUID categoryId, boolean published, boolean featured, boolean bestSeller,
            ObjectNode text, ArrayNode gallery, ArrayNode colors, ArrayNode sizes, List<String> methods,
            List<String> positions, Integer moq, Integer maxColors, Integer weightGsm) {

        String name() {
            return text.path("en").path("name").asText();
        }
    }

    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    public Mono<ObjectNode> createProduct(Staff actor, JsonNode body) {
        ProductRow input;
        try {
            input = productInput(body);
        } catch (ApiProblem problem) {
            return Mono.error(problem);
        }
        UUID id = UUID.randomUUID();
        return checkReferences(input)
                .then(bindProduct(db.sql("""
                        INSERT INTO products (id, category_id, name, slug, short_description, description, is_active,
                                              featured, web_text, best_seller, weight_gsm, minimum_order_quantity,
                                              maximum_print_colors, photos, colors, sizes, print_methods, print_positions,
                                              created_by, updated_by)
                        VALUES (:id, :category, :name, :slug, :short, :description, :active, :featured, :text, :best,
                                :weight, :moq, :colours, :photos, :colors, :sizes, :methods, :positions, :actor, :actor)
                        """), input, actor).bind("id", id).then())
                .then(Mono.defer(() -> records.activity(actor, "product", id, input.name(),
                        "Created (" + (input.published() ? "published" : "draft") + ")")))
                .as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> StaffContentService.slugTaken())
                .then(Mono.defer(() -> product(id)));
    }

    public Mono<ObjectNode> updateProduct(Staff actor, UUID id, JsonNode body) {
        ProductRow input;
        try {
            input = productInput(body);
        } catch (ApiProblem problem) {
            return Mono.error(problem);
        }
        List<String> keptPhotos = urls(input.gallery());
        return galleryUrls(id).flatMap(before -> checkReferences(input)
                .then(bindProduct(db.sql("""
                        WITH previous AS (SELECT id, is_active FROM products WHERE id = :id AND deleted_at IS NULL FOR UPDATE)
                        UPDATE products p SET category_id = :category, name = :name, slug = :slug, short_description = :short,
                               description = :description, is_active = :active, featured = :featured, web_text = :text,
                               best_seller = :best, weight_gsm = :weight, minimum_order_quantity = :moq,
                               maximum_print_colors = :colours, photos = :photos, colors = :colors, sizes = :sizes,
                               print_methods = :methods, print_positions = :positions, updated_by = :actor
                        FROM previous WHERE p.id = previous.id
                        RETURNING previous.is_active AS was_active
                        """), input, actor)
                        .bind("id", id)
                        .map(row -> Boolean.TRUE.equals(row.get("was_active", Boolean.class)))
                        .one())
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("product")))
                .flatMap(wasActive -> records.activity(actor, "product", id, input.name(),
                        wasActive == input.published() ? "Edited" : input.published() ? "Published" : "Moved back to draft"))
                .as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> StaffContentService.slugTaken())
                // After the commit: the photos taken off this product, if nothing else uses them.
                .then(Mono.defer(() -> media.removeUnused(StaffMediaService.dropped(before, keptPhotos)))))
                .then(Mono.defer(() -> product(id)));
    }

    /** The product's own photo addresses, as stored now. */
    private Mono<List<String>> galleryUrls(UUID id) {
        return db.sql("SELECT photos FROM products WHERE id = :id")
                .bind("id", id)
                .map(row -> urls(JsonNodes.column(json, row, "photos")))
                .one()
                .defaultIfEmpty(List.of());
    }

    /** The addresses in a photo list (MediaAsset[]). */
    static List<String> urls(JsonNode photos) {
        List<String> out = new ArrayList<>();
        if (photos != null && photos.isArray()) {
            photos.forEach(photo -> {
                String url = LocalizedText.text(photo.get("url"));
                if (url != null) {
                    out.add(url);
                }
            });
        }
        return out;
    }

    private ProductRow productInput(JsonNode body) {
        StaffContentService.requireObject(body);
        String category = LocalizedText.text(body.get("categoryId"));
        if (category == null) {
            throw ApiProblem.field("categoryId", "required", "Choose a category.");
        }
        List<String> methods = new ArrayList<>();
        for (JsonNode method : array(body.get("printMethods"), "printMethods")) {
            String value = LocalizedText.text(method);
            if (value == null || !METHODS.contains(value)) {
                throw ApiProblem.field("printMethods", "invalid", "Choose print methods from the list.");
            }
            methods.add(value);
        }
        List<String> positions = new ArrayList<>();
        for (JsonNode position : array(body.get("printPositions"), "printPositions")) {
            String value = LocalizedText.text(position);
            if (value == null || !POSITIONS.contains(value)) {
                throw ApiProblem.field("printPositions", "invalid", "Choose print positions from the list.");
            }
            positions.add(value);
        }
        if (!positions.isEmpty() && methods.isEmpty()) {
            throw ApiProblem.field("printMethods", "method_required", "Choose a print method for the print positions.");
        }
        return new ProductRow(
                LocalizedText.slug(LocalizedText.text(body.get("slug"))),
                uuidField(category, "categoryId", "Choose a category."),
                "published".equals(StaffContentService.status(body)),
                body.path("featured").asBoolean(false),
                body.path("bestSeller").asBoolean(false),
                LocalizedText.normalize(json, body.get("text"), PRODUCT_TEXT, "text"),
                MediaAssets.normalize(json, body.get("gallery"), "gallery", 60),
                colorsIn(body.get("colors")),
                sizesIn(body.get("sizes")),
                List.copyOf(new LinkedHashSet<>(methods)),
                List.copyOf(new LinkedHashSet<>(positions)),
                StaffContentService.positiveOrNull(body.get("minimumOrderQuantity"), "minimumOrderQuantity"),
                StaffContentService.positiveOrNull(body.get("maximumPrintColors"), "maximumPrintColors"),
                StaffContentService.positiveOrNull(body.get("weightGsm"), "weightGsm"));
    }

    /** The category must exist. */
    private Mono<Void> checkReferences(ProductRow input) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM categories WHERE id = :category AND deleted_at IS NULL) AS found")
                .bind("category", input.categoryId())
                .map(row -> Boolean.TRUE.equals(row.get("found", Boolean.class)))
                .one()
                .flatMap(found -> found ? Mono.<Void>empty()
                        : Mono.error(ApiProblem.field("categoryId", "invalid", "Choose an existing category.")));
    }

    /**
     * [{id, name, hex}]. A colour keeps its id; a new one (the editor sends a
     * temporary id such as "col-…") gets one. The same name and hex twice is
     * kept once.
     */
    private ArrayNode colorsIn(JsonNode node) {
        ArrayNode out = json.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode entry : array(node, "colors")) {
            String name = LocalizedText.text(entry.get("name"));
            String hex = LocalizedText.text(entry.get("hex"));
            if (name == null || name.length() > 100) {
                throw ApiProblem.field("colors", "invalid", "Name every colour (up to 100 characters).");
            }
            if (hex == null || !HEX.matcher(hex).matches()) {
                throw ApiProblem.field("colors", "invalid", "Give every colour a hex code such as #1D1038.");
            }
            if (seen.add(name.toLowerCase() + hex.toUpperCase())) {
                out.addObject()
                        .put("id", keptId(LocalizedText.text(entry.get("id"))))
                        .put("name", name)
                        .put("hex", hex.toUpperCase());
            }
        }
        return out;
    }

    /** [{id, label, measurements?}]; measurements belong to this product only. As for colours, by label. */
    private ArrayNode sizesIn(JsonNode node) {
        ArrayNode out = json.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode entry : array(node, "sizes")) {
            String label = LocalizedText.text(entry.get("label"));
            if (label == null || label.length() > 20) {
                throw ApiProblem.field("sizes", "invalid", "Label every size (up to 20 characters), e.g. XL.");
            }
            String measurements = LocalizedText.text(entry.get("measurements"));
            if (measurements != null && measurements.length() > 200) {
                throw ApiProblem.field("sizes", "too_long", "Keep each size's measurements under 200 characters.");
            }
            if (seen.add(label.toUpperCase())) {
                ObjectNode size = out.addObject()
                        .put("id", keptId(LocalizedText.text(entry.get("id"))))
                        .put("label", label);
                if (measurements != null) {
                    size.put("measurements", measurements);
                }
            }
        }
        return out;
    }

    /** The id an entry came with when it is one of ours, else a new one. */
    private static String keptId(String id) {
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException | NullPointerException e) {
            return UUID.randomUUID().toString();
        }
    }

    private GenericExecuteSpec bindProduct(GenericExecuteSpec spec, ProductRow input, Staff actor) {
        JsonNode en = input.text().path("en");
        spec = spec.bind("category", input.categoryId())
                .bind("name", input.name())
                .bind("slug", input.slug())
                .bind("active", input.published())
                .bind("featured", input.featured())
                .bind("text", Json.of(input.text().toString()))
                .bind("best", input.bestSeller())
                .bind("photos", Json.of(input.gallery().toString()))
                .bind("colors", Json.of(input.colors().toString()))
                .bind("sizes", Json.of(input.sizes().toString()))
                .bind("methods", input.methods().toArray(String[]::new))
                .bind("positions", input.positions().toArray(String[]::new))
                .bind("actor", actor.id());
        spec = StaffViews.bind(spec, "short", LocalizedText.text(en.get("shortDescription")), String.class);
        spec = StaffViews.bind(spec, "description", LocalizedText.text(en.get("description")), String.class);
        spec = StaffViews.bind(spec, "weight", input.weightGsm(), Integer.class);
        spec = StaffViews.bind(spec, "moq", input.moq(), Integer.class);
        return StaffViews.bind(spec, "colours", input.maxColors(), Integer.class);
    }

    // ---- Helpers ------------------------------------------------------------

    private static Iterable<JsonNode> array(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > 50) {
            throw ApiProblem.field(field, "invalid", "The request could not be read.");
        }
        return node;
    }

    private static UUID uuidField(String value, String field, String message) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiProblem.field(field, "invalid", message);
        }
    }

    private static void integer(ObjectNode out, String field, Integer value) {
        if (value == null) {
            out.putNull(field);
        } else {
            out.put(field, value);
        }
    }
}
