package com.braseth.ecommerce.modules.staff.service;

import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.common.util.JsonNodes;
import com.braseth.ecommerce.common.util.Paging;
import com.braseth.ecommerce.modules.auth.service.AuthService;
import com.braseth.ecommerce.modules.auth.service.RefreshTokenService;
import com.braseth.ecommerce.modules.order.service.WebOrderViews;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.AccountStatusChange;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.OrderNote;
import com.braseth.ecommerce.modules.staff.service.StaffGate.Staff;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.r2dbc.spi.Readable;
import reactor.core.publisher.Mono;

/**
 * Customer accounts from the staff side (AdminCustomer): find them, see
 * their history, suspend or reactivate, send a password reset, keep notes.
 * Accounts only — a guest who sent a quote request appears on the quote and
 * order screens instead.
 */
@Service
public class StaffCustomersService {

    /** Accounts with the customer role that are not also an admin. */
    private static final String CUSTOMER = """
            'ROLE_CUSTOMER' = ANY(u.roles)
            AND NOT 'ROLE_ADMIN' = ANY(u.roles)
            AND u.deleted_at IS NULL AND u.status IN ('active', 'suspended')
            """;

    private static final String SELECT = """
            SELECT u.id, u.name, u.email, u.phone, u.status, u.email_verified_at, u.created_at,
                   u.company_name, u.telegram, u.preferred_contact_method, u.suspended_reason,
                   u.address, u.city, u.province, u.postal_code, u.country, u.company,
                   (SELECT count(*) FROM quote_requests q WHERE q.customer_id = u.id) AS quote_count,
                   (SELECT count(*) FROM orders o WHERE o.customer_id = u.id) AS order_count,
                   (SELECT count(*) FROM orders o WHERE o.customer_id = u.id
                      AND o.status NOT IN ('delivered', 'completed', 'cancelled')) AS open_order_count,
                   greatest(u.created_at,
                            (SELECT max(q.created_at) FROM quote_requests q WHERE q.customer_id = u.id),
                            (SELECT max(o.created_at) FROM orders o WHERE o.customer_id = u.id)) AS last_activity_at,
                   (SELECT jsonb_object_agg(t.currency, t.owed) FROM (
                        SELECT o.currency, sum(greatest(o.total - coalesce(
                                   (SELECT sum(p.amount) FROM order_payments p WHERE p.order_id = o.id), 0), 0)) AS owed
                        FROM orders o
                        WHERE o.customer_id = u.id AND o.status <> 'cancelled' AND o.total IS NOT NULL
                        GROUP BY o.currency) t) AS owed
            FROM users u
            """;

    private final DatabaseClient db;
    private final ObjectMapper json;
    private final StaffQuotesService quotes;
    private final WebOrderViews orders;
    private final AuthService auth;
    private final RefreshTokenService refreshTokens;
    private final StaffRecords records;
    private final TransactionalOperator transaction;

    StaffCustomersService(DatabaseClient db, ObjectMapper json, StaffQuotesService quotes, WebOrderViews orders,
            AuthService auth, RefreshTokenService refreshTokens, StaffRecords records,
            ReactiveTransactionManager transactionManager) {
        this.db = db;
        this.json = json;
        this.quotes = quotes;
        this.orders = orders;
        this.auth = auth;
        this.refreshTokens = refreshTokens;
        this.records = records;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    // ---- Reading ------------------------------------------------------------

    public Mono<ObjectNode> list(String kind, String status, String q, int page, int size) {
        int safePage = StaffViews.page(page);
        int safeSize = StaffViews.size(size);
        String where = " WHERE " + CUSTOMER + """
                  AND (CAST(:kind AS text) IS NULL
                       OR (:kind = 'corporate') = (coalesce(u.company_name, '') <> ''))
                  AND (CAST(:status AS text) IS NULL OR u.status = :status)
                  AND (CAST(:q AS text) IS NULL
                       OR strpos(lower(u.name), lower(:q)) > 0
                       OR strpos(lower(coalesce(u.company_name, '')), lower(:q)) > 0
                       OR strpos(lower(u.email), lower(:q)) > 0
                       OR strpos(regexp_replace(coalesce(u.phone, ''), '[^0-9]', '', 'g'), regexp_replace(:q, '[^0-9]', '', 'g')) > 0
                          AND regexp_replace(:q, '[^0-9]', '', 'g') <> '')
                """;
        GenericExecuteSpec rows = filters(db.sql(SELECT + where + " ORDER BY last_activity_at DESC, u.id LIMIT :limit OFFSET :offset"),
                kind, status, q)
                .bind("limit", safeSize)
                .bind("offset", (safePage - 1) * safeSize);
        GenericExecuteSpec count = filters(db.sql(
                "SELECT count(*) AS n FROM users u" + where), kind, status, q);
        return Mono.zip(rows.map(this::summary).all().collectList(), count.map(row -> row.get("n", Long.class)).one())
                .map(parts -> Paging.page(json, parts.getT1(), safePage, safeSize, parts.getT2()));
    }

    public Mono<ObjectNode> detail(UUID id) {
        Mono<ObjectNode> base = db.sql(SELECT + " WHERE u.id = :id AND " + CUSTOMER)
                .bind("id", id)
                .map(row -> {
                    ObjectNode out = summary(row);
                    StaffViews.text(out, "telegram", row.get("telegram", String.class));
                    String contact = row.get("preferred_contact_method", String.class);
                    out.put("preferredContactMethod", contact == null ? "email" : contact);
                    out.put("emailVerified", row.get("email_verified_at", OffsetDateTime.class) != null);
                    String street = row.get("address", String.class);
                    if (street != null && !street.isBlank()) {
                        String[] lines = street.split("\n", 2);
                        ObjectNode address = out.putObject("address").put("line1", lines[0]);
                        if (lines.length > 1) {
                            address.put("line2", lines[1]);
                        }
                        address.put("city", nullToEmpty(row.get("city", String.class)));
                        putIf(address, "province", row.get("province", String.class));
                        putIf(address, "postalCode", row.get("postal_code", String.class));
                        address.put("country", nullToEmpty(row.get("country", String.class)));
                    }
                    StaffViews.text(out, "suspendedReason", row.get("suspended_reason", String.class));
                    JsonNode company = company(id, row.get("company_name", String.class), JsonNodes.column(json, row, "company"));
                    out.set("company", company);
                    if (company.isObject()) {
                        out.put("companyId", id.toString());
                    }
                    return out;
                })
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("customer")));
        return base.flatMap(out -> Mono.zip(recentQuotes(id), recentOrders(id), notes(id)).map(parts -> {
            out.set("recentQuotes", parts.getT1());
            out.set("recentOrders", parts.getT2());
            out.set("internalNotes", parts.getT3());
            return out;
        }));
    }

    /** The company details saved from the customer's account (users.company); null for an individual. */
    private JsonNode company(UUID id, String name, JsonNode stored) {
        if (!stored.isObject()) {
            return NullNode.getInstance();
        }
        ObjectNode out = json.createObjectNode();
        out.put("id", id.toString());
        out.put("name", nullToEmpty(name));
        if (stored.path("address").isObject()) {
            out.set("address", stored.get("address"));
        }
        for (String field : new String[] { "contactPersonName", "contactPersonRole", "contactEmail", "contactPhone",
                "taxId", "industry", "website" }) {
            putIf(out, field, stored.path(field).asText(null));
        }
        return out;
    }

    private Mono<ArrayNode> recentQuotes(UUID id) {
        return db.sql(StaffQuotesService.SELECT + " WHERE q.customer_id = :id ORDER BY q.created_at DESC LIMIT 5")
                .bind("id", id)
                .map(row -> quotes.summary(row))
                .all()
                .collect(json::createArrayNode, ArrayNode::add);
    }

    private Mono<ArrayNode> recentOrders(UUID id) {
        return db.sql(WebOrderViews.SELECT + " WHERE o.customer_id = :id ORDER BY o.created_at DESC LIMIT 5")
                .bind("id", id)
                .map(orders::adminSummary)
                .all()
                .collect(json::createArrayNode, ArrayNode::add);
    }

    private Mono<ArrayNode> notes(UUID id) {
        return db.sql("""
                SELECT n.id, n.body, n.at, u.name AS author_name FROM activity_log n LEFT JOIN users u ON u.id = n.actor_id
                WHERE n.kind = 'customer-note' AND n.subject_id = :id ORDER BY n.at DESC
                """)
                .bind("id", id)
                .map(row -> StaffOrdersService.note(json, row))
                .all()
                .collect(json::createArrayNode, ArrayNode::add);
    }

    // ---- Changing -----------------------------------------------------------

    /**
     * Suspends or reactivates the account. Suspending signs the customer out
     * everywhere; the token filter re-reads the status on every request, so
     * a session already open stops working at once.
     */
    public Mono<ObjectNode> changeStatus(Staff actor, UUID id, AccountStatusChange change) {
        boolean suspend = "suspended".equals(change.status());
        String reason = change.reason() == null || change.reason().isBlank() ? null : change.reason().trim();
        if (suspend && reason == null) {
            return Mono.error(ApiProblem.field("reason", "reason_required", "Say why, so the next person knows."));
        }
        return name(id)
                .flatMap(name -> db.sql("UPDATE users SET status = :status WHERE id = :id AND status <> :status")
                        .bind("status", change.status())
                        .bind("id", id)
                        .fetch()
                        .rowsUpdated()
                        .flatMap(updated -> updated == 0
                                ? Mono.error(new ApiProblem(HttpStatus.CONFLICT, "same_status",
                                        suspend ? "This account is already suspended." : "This account is already active."))
                                : StaffViews.bind(db.sql("UPDATE users SET suspended_reason = :reason WHERE id = :id")
                                        .bind("id", id), "reason", suspend ? reason : null, String.class)
                                        .then())
                        .then(suspend ? refreshTokens.revokeAll(id) : Mono.empty())
                        .then(records.activity(actor, "customer", id, name,
                                suspend ? "Suspended: " + reason : "Reactivated the account")))
                .as(transaction::transactional)
                .then(Mono.defer(() -> detail(id)));
    }

    public Mono<Void> sendPasswordReset(Staff actor, UUID id) {
        return name(id)
                .flatMap(name -> auth.sendPasswordReset(id)
                        .flatMap(sent -> sent
                                ? records.activity(actor, "customer", id, name, "Sent a password reset link")
                                : Mono.error(new ApiProblem(HttpStatus.CONFLICT, "cannot_reset",
                                        "This account cannot sign in (suspended or not yet confirmed), so no link was sent."))));
    }

    public Mono<ObjectNode> addNote(Staff actor, UUID id, OrderNote note) {
        return name(id)
                .flatMap(name -> db.sql("""
                        INSERT INTO activity_log (kind, subject_id, body, actor_id, actor_name)
                        VALUES ('customer-note', :id, :body, :actor, :name)
                        """)
                        .bind("id", id)
                        .bind("body", note.body().trim())
                        .bind("actor", actor.id())
                        .bind("name", actor.name())
                        .then()
                        .then(records.activity(actor, "customer", id, name, "Added an internal note")))
                .as(transaction::transactional)
                .then(Mono.defer(() -> detail(id)));
    }

    // ---- Views and helpers --------------------------------------------------

    /** AdminCustomerSummary. */
    private ObjectNode summary(Readable row) {
        ObjectNode out = json.createObjectNode();
        out.put("id", row.get("id", UUID.class).toString());
        out.put("fullName", row.get("name", String.class));
        String company = row.get("company_name", String.class);
        StaffViews.text(out, "companyName", company);
        out.put("email", row.get("email", String.class));
        out.put("phone", nullToEmpty(row.get("phone", String.class)));
        out.put("kind", company == null || company.isBlank() ? "individual" : "corporate");
        out.put("status", row.get("status", String.class));
        out.put("createdAt", row.get("created_at", OffsetDateTime.class).toInstant().toString());
        out.put("lastActivityAt", row.get("last_activity_at", OffsetDateTime.class).toInstant().toString());
        out.put("quoteCount", row.get("quote_count", Long.class));
        out.put("orderCount", row.get("order_count", Long.class));
        out.put("openOrderCount", row.get("open_order_count", Long.class));
        out.set("amountOwed", owed(JsonNodes.column(json, row, "owed")));
        return out;
    }

    /** What is still due, in dollars when they have any dollar orders, otherwise in riel. */
    private JsonNode owed(JsonNode byCurrency) {
        for (String currency : new String[] { "USD", "KHR" }) {
            if (byCurrency.has(currency)) {
                return orders.money(byCurrency.get(currency).decimalValue().setScale(2, RoundingMode.HALF_UP), currency);
            }
        }
        return NullNode.getInstance();
    }

    private Mono<String> name(UUID id) {
        return db.sql("SELECT u.name FROM users u WHERE u.id = :id AND " + CUSTOMER)
                .bind("id", id)
                .map(row -> row.get("name", String.class))
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("customer")));
    }

    private static GenericExecuteSpec filters(GenericExecuteSpec spec, String kind, String status, String q) {
        spec = StaffViews.bind(spec, "kind", kind, String.class);
        spec = StaffViews.bind(spec, "status", status, String.class);
        return StaffViews.bind(spec, "q", q, String.class);
    }

    private static void putIf(ObjectNode out, String field, String value) {
        if (value != null && !value.isBlank()) {
            out.put(field, value);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
