package com.braseth.ecommerce.modules.staff.service;

import static com.braseth.ecommerce.modules.staff.service.LocalizedText.Field.list;
import static com.braseth.ecommerce.modules.staff.service.LocalizedText.Field.optional;
import static com.braseth.ecommerce.modules.staff.service.LocalizedText.Field.required;
import static com.braseth.ecommerce.modules.staff.service.LocalizedText.Field.text;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Readable;
import reactor.core.publisher.Mono;

/**
 * Services and the portfolio, as staff edit them (AdminService,
 * AdminPortfolioProject). The public pages read the same rows: only
 * published ones, and a client is named only with their permission.
 */
@Service
public class StaffContentService {

    static final Set<String> PRINT_METHODS = Set.of("silkscreen", "embroidery", "dtf", "heat-transfer", "sublimation", "pad-printing");

    private static final Set<String> PORTFOLIO_CATEGORIES = Set.of("t-shirts", "polo", "bags", "embroidery", "corporate", "events", "promotional");

    static final List<Field> SERVICE_TEXT = List.of(
            required("name", 200), text("shortDescription", 500), text("description", 10000),
            list("benefits", 300), list("useCases", 300), optional("turnaround", 200));

    private static final List<Field> STEP_TEXT = List.of(required("title", 200), text("description", 2000));

    private static final List<Field> PORTFOLIO_TEXT = List.of(
            required("title", 200), text("summary", 5000), optional("productType", 200), optional("printMethod", 200));

    private final DatabaseClient db;
    private final ObjectMapper json;
    private final StaffRecords records;
    private final StaffMediaService media;
    private final TransactionalOperator transaction;

    StaffContentService(DatabaseClient db, ObjectMapper json, StaffRecords records, StaffMediaService media,
            ReactiveTransactionManager transactionManager) {
        this.db = db;
        this.json = json;
        this.records = records;
        this.media = media;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    // ---- Services -----------------------------------------------------------

    /** Every service, drafts too, in the order the public Services page shows them. */
    public Mono<ArrayNode> services() {
        return db.sql("SELECT * FROM services WHERE deleted_at IS NULL ORDER BY position, created_at")
                .map(this::serviceSummary)
                .all()
                .collect(json::createArrayNode, ArrayNode::add);
    }

    public Mono<ObjectNode> service(UUID id) {
        return db.sql("SELECT s.*, u.name AS updated_by_name FROM services s LEFT JOIN users u ON u.id = s.updated_by "
                + "WHERE s.id = :id AND s.deleted_at IS NULL")
                .bind("id", id)
                .map(row -> {
                    ObjectNode out = json.createObjectNode();
                    out.put("id", row.get("id", UUID.class).toString());
                    out.put("slug", row.get("slug", String.class));
                    out.put("status", row.get("status", String.class));
                    StaffViews.text(out, "method", row.get("method", String.class));
                    out.set("photos", JsonNodes.column(json, row, "photos"));
                    out.set("applicableCategorySlugs", JsonNodes.column(json, row, "applicable_category_slugs"));
                    Integer moq = row.get("minimum_order_quantity", Integer.class);
                    if (moq == null) {
                        out.putNull("minimumOrderQuantity");
                    } else {
                        out.put("minimumOrderQuantity", moq);
                    }
                    out.set("text", LocalizedText.read(json, JsonNodes.column(json, row, "service_text"), SERVICE_TEXT));
                    ArrayNode process = out.putArray("process");
                    for (JsonNode step : JsonNodes.column(json, row, "process")) {
                        process.add(LocalizedText.read(json, step, STEP_TEXT));
                    }
                    StaffViews.instant(out, "updatedAt", row.get("updated_at", OffsetDateTime.class));
                    StaffViews.text(out, "updatedBy", row.get("updated_by_name", String.class));
                    return out;
                })
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("service")));
    }

    /** A new service goes to the end of the list. */
    public Mono<ObjectNode> createService(Staff actor, JsonNode body) {
        ServiceRow input;
        try {
            input = serviceInput(body);
        } catch (ApiProblem problem) {
            return Mono.error(problem);
        }
        return bindService(db.sql("""
                INSERT INTO services (slug, status, method, photos, applicable_category_slugs, minimum_order_quantity,
                                      service_text, process, position, updated_at, updated_by)
                VALUES (:slug, :status, :method, :photos, :categories, :moq, :text, :process,
                        (SELECT coalesce(max(position), 0) + 1 FROM services WHERE deleted_at IS NULL), now(), :actor)
                RETURNING id
                """), input, actor)
                .map(row -> row.get("id", UUID.class))
                .one()
                .flatMap(id -> records.activity(actor, "service", id, input.name(), "Created (" + input.status() + ")")
                        .thenReturn(id))
                .as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> slugTaken())
                .flatMap(this::service);
    }

    public Mono<ObjectNode> updateService(Staff actor, UUID id, JsonNode body) {
        ServiceRow input;
        try {
            input = serviceInput(body);
        } catch (ApiProblem problem) {
            return Mono.error(problem);
        }
        return storedPhotos("services", id).flatMap(before -> bindService(db.sql("""
                WITH previous AS (SELECT id, status FROM services WHERE id = :id AND deleted_at IS NULL FOR UPDATE)
                UPDATE services s SET slug = :slug, status = :status, method = :method, photos = :photos,
                       applicable_category_slugs = :categories, minimum_order_quantity = :moq,
                       service_text = :text, process = :process, updated_at = now(), updated_by = :actor
                FROM previous WHERE s.id = previous.id
                RETURNING previous.status AS previous
                """), input, actor)
                .bind("id", id)
                .map(row -> row.get("previous", String.class))
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("service")))
                .flatMap(previous -> records.activity(actor, "service", id, input.name(), changeSummary(previous, input.status())))
                .as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> slugTaken())
                // After the commit: photos taken off this service, if nothing else uses them.
                .then(Mono.defer(() -> media.removeUnused(
                        StaffMediaService.dropped(before, StaffCatalogueService.urls(input.photos()))))))
                .then(Mono.defer(() -> service(id)));
    }

    /** The complete new order: every service, each once. */
    public Mono<ArrayNode> orderServices(Staff actor, List<String> ids) {
        List<UUID> order = ids.stream().map(id -> StaffViews.uuid(id, "service")).toList();
        if (new HashSet<>(order).size() != order.size()) {
            return Mono.error(ApiProblem.field("ids", "duplicate", "List each service once."));
        }
        return db.sql("SELECT id FROM services WHERE deleted_at IS NULL")
                .map(row -> row.get("id", UUID.class))
                .all()
                .collectList()
                .flatMap(existing -> {
                    if (existing.size() != order.size() || !new HashSet<>(existing).containsAll(order)) {
                        return Mono.error(new ApiProblem(HttpStatus.CONFLICT, "stale_order",
                                "The list of services has changed. Reload and try again."));
                    }
                    return db.sql("""
                            UPDATE services s SET position = o.n
                            FROM unnest(CAST(:ids AS uuid[])) WITH ORDINALITY AS o(id, n)
                            WHERE s.id = o.id
                            """)
                            .bind("ids", order.toArray(UUID[]::new))
                            .then()
                            .then(records.activity(actor, "service", null, "Services", "Changed the order of services"));
                })
                .as(transaction::transactional)
                .then(Mono.defer(this::services));
    }

    private record ServiceRow(String slug, String status, String method, ArrayNode photos, ArrayNode categories,
            Integer moq, ObjectNode text, ArrayNode process) {

        String name() {
            return text.path("en").path("name").asText();
        }
    }

    private ServiceRow serviceInput(JsonNode body) {
        requireObject(body);
        String method = LocalizedText.text(body.get("method"));
        if (method != null && !PRINT_METHODS.contains(method)) {
            throw ApiProblem.field("method", "invalid", "Choose a print method, or none.");
        }
        ArrayNode categories = json.createArrayNode();
        JsonNode slugs = body.get("applicableCategorySlugs");
        if (slugs != null && slugs.isArray()) {
            if (slugs.size() > 50) {
                throw ApiProblem.field("applicableCategorySlugs", "too_many", "Choose at most 50 categories.");
            }
            slugs.forEach(slug -> {
                String value = LocalizedText.text(slug);
                if (value != null) {
                    categories.add(LocalizedText.slug(value));
                }
            });
        }
        ArrayNode process = json.createArrayNode();
        JsonNode steps = body.get("process");
        if (steps != null && steps.isArray()) {
            if (steps.size() > 20) {
                throw ApiProblem.field("process", "too_many", "Keep it to 20 steps.");
            }
            for (int i = 0; i < steps.size(); i++) {
                process.add(LocalizedText.normalize(json, steps.get(i), STEP_TEXT, "process." + i));
            }
        }
        return new ServiceRow(
                LocalizedText.slug(LocalizedText.text(body.get("slug"))),
                status(body),
                method,
                MediaAssets.normalize(json, body.get("photos"), "photos", 60),
                categories,
                positiveOrNull(body.get("minimumOrderQuantity"), "minimumOrderQuantity"),
                LocalizedText.normalize(json, body.get("text"), SERVICE_TEXT, "text"),
                process);
    }

    private GenericExecuteSpec bindService(GenericExecuteSpec spec, ServiceRow input, Staff actor) {
        spec = spec.bind("slug", input.slug())
                .bind("status", input.status())
                .bind("photos", Json.of(input.photos().toString()))
                .bind("categories", Json.of(input.categories().toString()))
                .bind("text", Json.of(input.text().toString()))
                .bind("process", Json.of(input.process().toString()))
                .bind("actor", actor.id());
        spec = StaffViews.bind(spec, "method", input.method(), String.class);
        return StaffViews.bind(spec, "moq", input.moq(), Integer.class);
    }

    private ObjectNode serviceSummary(Readable row) {
        JsonNode text = JsonNodes.column(json, row, "service_text");
        JsonNode photos = JsonNodes.column(json, row, "photos");
        ObjectNode out = json.createObjectNode();
        out.put("id", row.get("id", UUID.class).toString());
        out.put("slug", row.get("slug", String.class));
        out.put("name", text.path("en").path("name").asText(""));
        out.put("status", row.get("status", String.class));
        StaffViews.text(out, "method", row.get("method", String.class));
        out.set("thumbnail", photos.size() > 0 ? photos.get(0) : NullNode.getInstance());
        boolean complete = LocalizedText.khmerComplete(text, SERVICE_TEXT);
        for (JsonNode step : JsonNodes.column(json, row, "process")) {
            complete &= LocalizedText.khmerComplete(step, STEP_TEXT);
        }
        out.put("khmerComplete", complete);
        return out;
    }

    // ---- Portfolio ----------------------------------------------------------

    /** Newest completed first, undated after dated, as the public portfolio shows them. */
    public Mono<ObjectNode> portfolio(String category, String status, int page, int size) {
        int safePage = StaffViews.page(page);
        int safeSize = StaffViews.size(size);
        String where = """
                WHERE p.deleted_at IS NULL
                  AND (CAST(:category AS text) IS NULL OR p.category = :category)
                  AND (CAST(:status AS text) IS NULL OR p.status = :status)
                """;
        GenericExecuteSpec rows = StaffViews.bind(StaffViews.bind(db.sql("SELECT p.* FROM portfolio_projects p " + where
                + " ORDER BY p.completed_on DESC NULLS LAST, p.created_at DESC LIMIT :limit OFFSET :offset"),
                "category", category, String.class), "status", status, String.class)
                .bind("limit", safeSize)
                .bind("offset", (safePage - 1) * safeSize);
        GenericExecuteSpec count = StaffViews.bind(StaffViews.bind(db.sql("SELECT count(*) AS n FROM portfolio_projects p " + where),
                "category", category, String.class), "status", status, String.class);
        return Mono.zip(rows.map(this::portfolioSummary).all().collectList(), count.map(row -> row.get("n", Long.class)).one())
                .map(parts -> Paging.page(json, parts.getT1(), safePage, safeSize, parts.getT2()));
    }

    public Mono<ObjectNode> project(UUID id) {
        return db.sql("SELECT p.*, u.name AS updated_by_name FROM portfolio_projects p LEFT JOIN users u ON u.id = p.updated_by "
                + "WHERE p.id = :id AND p.deleted_at IS NULL")
                .bind("id", id)
                .map(row -> {
                    ObjectNode out = json.createObjectNode();
                    out.put("id", row.get("id", UUID.class).toString());
                    out.put("slug", row.get("slug", String.class));
                    out.put("status", row.get("status", String.class));
                    out.put("category", row.get("category", String.class));
                    StaffViews.date(out, "completedOn", row.get("completed_on", LocalDate.class));
                    out.set("photos", JsonNodes.column(json, row, "photos"));
                    out.set("text", LocalizedText.read(json, JsonNodes.column(json, row, "project_text"), PORTFOLIO_TEXT));
                    StaffViews.text(out, "clientName", row.get("client_name", String.class));
                    out.put("clientNamePermitted", Boolean.TRUE.equals(row.get("client_name_permitted", Boolean.class)));
                    StaffViews.instant(out, "updatedAt", row.get("updated_at", OffsetDateTime.class));
                    StaffViews.text(out, "updatedBy", row.get("updated_by_name", String.class));
                    return out;
                })
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("project")));
    }

    public Mono<ObjectNode> createProject(Staff actor, JsonNode body) {
        ProjectRow input;
        try {
            input = projectInput(body);
        } catch (ApiProblem problem) {
            return Mono.error(problem);
        }
        return bindProject(db.sql("""
                INSERT INTO portfolio_projects (slug, status, category, completed_on, photos, project_text, client_name,
                                                client_name_permitted, updated_at, updated_by)
                VALUES (:slug, :status, :category, :completed, :photos, :text, :client, :permitted, now(), :actor)
                RETURNING id
                """), input, actor)
                .map(row -> row.get("id", UUID.class))
                .one()
                .flatMap(id -> records.activity(actor, "portfolio", id, input.title(), "Created (" + input.status() + ")")
                        .thenReturn(id))
                .as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> slugTaken())
                .flatMap(this::project);
    }

    public Mono<ObjectNode> updateProject(Staff actor, UUID id, JsonNode body) {
        ProjectRow input;
        try {
            input = projectInput(body);
        } catch (ApiProblem problem) {
            return Mono.error(problem);
        }
        return storedPhotos("portfolio_projects", id).flatMap(before -> bindProject(db.sql("""
                WITH previous AS (SELECT id, status FROM portfolio_projects WHERE id = :id AND deleted_at IS NULL FOR UPDATE)
                UPDATE portfolio_projects p SET slug = :slug, status = :status, category = :category,
                       completed_on = :completed, photos = :photos, project_text = :text, client_name = :client,
                       client_name_permitted = :permitted, updated_at = now(), updated_by = :actor
                FROM previous WHERE p.id = previous.id
                RETURNING previous.status AS previous
                """), input, actor)
                .bind("id", id)
                .map(row -> row.get("previous", String.class))
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("project")))
                .flatMap(previous -> records.activity(actor, "portfolio", id, input.title(), changeSummary(previous, input.status())))
                .as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> slugTaken())
                // After the commit: photos taken off this project, if nothing else uses them.
                .then(Mono.defer(() -> media.removeUnused(
                        StaffMediaService.dropped(before, StaffCatalogueService.urls(input.photos()))))))
                .then(Mono.defer(() -> project(id)));
    }

    /**
     * The photo addresses a service or project has now. {@code table} is one
     * of the two names above, never from a request. Empty when the row is
     * missing; the update then answers 404 itself.
     */
    private Mono<List<String>> storedPhotos(String table, UUID id) {
        return db.sql("SELECT photos FROM " + table + " WHERE id = :id")
                .bind("id", id)
                .map(row -> StaffCatalogueService.urls(JsonNodes.column(json, row, "photos")))
                .one()
                .defaultIfEmpty(List.of());
    }

    private record ProjectRow(String slug, String status, String category, LocalDate completedOn, ArrayNode photos,
            ObjectNode text, String clientName, boolean clientNamePermitted) {

        String title() {
            return text.path("en").path("title").asText();
        }
    }

    private ProjectRow projectInput(JsonNode body) {
        requireObject(body);
        String category = LocalizedText.text(body.get("category"));
        if (category == null || !PORTFOLIO_CATEGORIES.contains(category)) {
            throw ApiProblem.field("category", "invalid", "Choose a category.");
        }
        LocalDate completed = null;
        String completedText = LocalizedText.text(body.get("completedOn"));
        if (completedText != null) {
            try {
                completed = LocalDate.parse(completedText);
            } catch (DateTimeParseException e) {
                throw ApiProblem.field("completedOn", "invalid_date", "Choose a date.");
            }
        }
        String client = LocalizedText.text(body.get("clientName"));
        if (client != null && client.length() > 200) {
            throw ApiProblem.field("clientName", "too_long", "Keep the name under 200 characters.");
        }
        return new ProjectRow(
                LocalizedText.slug(LocalizedText.text(body.get("slug"))),
                status(body),
                category,
                completed,
                MediaAssets.normalize(json, body.get("photos"), "photos", 60),
                LocalizedText.normalize(json, body.get("text"), PORTFOLIO_TEXT, "text"),
                client,
                // Permission without a name to show means nothing; keep it off.
                client != null && body.path("clientNamePermitted").asBoolean(false));
    }

    private GenericExecuteSpec bindProject(GenericExecuteSpec spec, ProjectRow input, Staff actor) {
        spec = spec.bind("slug", input.slug())
                .bind("status", input.status())
                .bind("category", input.category())
                .bind("photos", Json.of(input.photos().toString()))
                .bind("text", Json.of(input.text().toString()))
                .bind("permitted", input.clientNamePermitted())
                .bind("actor", actor.id());
        spec = StaffViews.bind(spec, "completed", input.completedOn(), LocalDate.class);
        return StaffViews.bind(spec, "client", input.clientName(), String.class);
    }

    private ObjectNode portfolioSummary(Readable row) {
        JsonNode text = JsonNodes.column(json, row, "project_text");
        JsonNode photos = JsonNodes.column(json, row, "photos");
        ObjectNode out = json.createObjectNode();
        out.put("id", row.get("id", UUID.class).toString());
        out.put("slug", row.get("slug", String.class));
        out.put("title", text.path("en").path("title").asText(""));
        out.put("category", row.get("category", String.class));
        out.set("cover", photos.size() > 0 ? photos.get(0) : NullNode.getInstance());
        out.put("status", row.get("status", String.class));
        StaffViews.date(out, "completedOn", row.get("completed_on", LocalDate.class));
        String client = row.get("client_name", String.class);
        out.put("clientNamed", client != null && !client.isBlank()
                && Boolean.TRUE.equals(row.get("client_name_permitted", Boolean.class)));
        out.put("khmerComplete", LocalizedText.khmerComplete(text, PORTFOLIO_TEXT));
        return out;
    }

    // ---- Shared -------------------------------------------------------------

    static void requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiProblem.badRequest("bad_request", "The request could not be read.");
        }
    }

    static String status(JsonNode body) {
        String status = LocalizedText.text(body.get("status"));
        if (!"published".equals(status) && !"draft".equals(status)) {
            throw ApiProblem.field("status", "invalid", "Choose published or draft.");
        }
        return status;
    }

    static Integer positiveOrNull(JsonNode node, String field) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        if (!node.canConvertToInt() || node.asInt() < 1 || node.asInt() > 1_000_000) {
            throw ApiProblem.field(field, "invalid_number", "Enter a whole number of 1 or more, or leave it empty.");
        }
        return node.asInt();
    }

    static String changeSummary(String previous, String now) {
        if (previous != null && !previous.equals(now)) {
            return "published".equals(now) ? "Published" : "Moved back to draft";
        }
        return "Edited";
    }

    static ApiProblem slugTaken() {
        return ApiProblem.conflict("slug", "slug_taken", "Another page already uses this address. Choose a different one.");
    }
}
