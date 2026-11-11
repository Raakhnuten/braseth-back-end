package com.braseth.ecommerce.modules.account.service;

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
import com.braseth.ecommerce.modules.order.service.WebOrderFactory;
import com.braseth.ecommerce.modules.order.service.WebOrderViews;
import com.braseth.ecommerce.modules.staff.service.StaffRecords;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.r2dbc.spi.Readable;
import reactor.core.publisher.Mono;

/**
 * A customer's own quote requests (the web app's Quote / QuoteSummary).
 * Every query is scoped to the signed-in customer, so another customer's
 * quote reads as not found rather than forbidden.
 */
@Service
public class MyQuotesService {

    private static final String SELECT = """
            SELECT q.*, %s, to_jsonb(q.design_file_ids) AS design_ids
            FROM quote_requests q
            """.formatted(WebOrderViews.QUOTE_LINE_TOTALS);

    private final DatabaseClient db;
    private final ObjectMapper json;
    private final WebOrderFactory orders;
    private final StaffRecords records;
    private final TransactionalOperator transaction;

    MyQuotesService(DatabaseClient db, ObjectMapper json, WebOrderFactory orders, StaffRecords records,
            ReactiveTransactionManager transactionManager) {
        this.db = db;
        this.json = json;
        this.orders = orders;
        this.records = records;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    public Mono<ObjectNode> list(UUID customerId, String status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 48);
        GenericExecuteSpec spec = db.sql(SELECT + """
                WHERE q.customer_id = :customer AND (CAST(:status AS text) IS NULL OR q.status = :status)
                ORDER BY q.created_at DESC
                LIMIT :limit OFFSET :offset
                """)
                .bind("customer", customerId)
                .bind("limit", safeSize)
                .bind("offset", (safePage - 1) * safeSize);
        spec = status == null || status.isBlank() ? spec.bindNull("status", String.class) : spec.bind("status", status);
        Mono<Long> total = (status == null || status.isBlank()
                ? db.sql("SELECT count(*) AS n FROM quote_requests WHERE customer_id = :customer")
                : db.sql("SELECT count(*) AS n FROM quote_requests WHERE customer_id = :customer AND status = :status")
                        .bind("status", status))
                .bind("customer", customerId)
                .map(row -> row.get("n", Long.class))
                .one();
        return Mono.zip(spec.map(row -> summary(row)).all().collectList(), total)
                .map(parts -> Paging.page(json, parts.getT1(), safePage, safeSize, parts.getT2()));
    }

    public Mono<ObjectNode> detail(UUID customerId, UUID quoteId) {
        return expireIfDue(quoteId)
                .then(db.sql(SELECT + " WHERE q.id = :id AND q.customer_id = :customer")
                        .bind("id", quoteId)
                        .bind("customer", customerId)
                        .map(row -> detailView(row))
                        .one())
                .switchIfEmpty(Mono.error(() -> notFound()));
    }

    /**
     * Accept or decline a price. Only a quote that has been priced and sent
     * (awaiting-approval) can be decided, and only once.
     */
    public Mono<ObjectNode> decide(UUID customerId, UUID quoteId, boolean accepted, String note) {
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (cleanNote != null && cleanNote.length() > 2000) {
            return Mono.error(ApiProblem.field("note", "too_long", "Keep the note under 2,000 characters."));
        }
        GenericExecuteSpec spec = db.sql("""
                UPDATE quote_requests
                SET status = :status, decided_at = now(), decision_note = :note, updated_at = now()
                WHERE id = :id AND customer_id = :customer AND status = 'awaiting-approval'
                  AND (expires_at IS NULL OR expires_at > now())
                RETURNING id, reference, full_name
                """)
                .bind("status", accepted ? "approved" : "rejected")
                .bind("id", quoteId)
                .bind("customer", customerId);
        spec = cleanNote == null ? spec.bindNull("note", String.class) : spec.bind("note", cleanNote);
        // The status change, the order it creates and the staff alert happen together or not at all.
        Mono<UUID> decided = spec.map(row -> new String[] { row.get("id", UUID.class).toString(),
                        row.get("reference", String.class), row.get("full_name", String.class) })
                .one()
                .flatMap(q -> (accepted ? orders.fromQuote(quoteId, customerId).then() : Mono.<Void>empty())
                        .then(records.alert(accepted ? "quote-approved" : "quote-declined", "quotes.view",
                                accepted ? "Quote approved by the customer" : "Quote declined by the customer",
                                q[1] + " · " + q[2] + (accepted || cleanNote == null ? "" : " — " + cleanNote),
                                "/admin/quotes/" + q[0]))
                        .thenReturn(quoteId))
                .as(transaction::transactional);
        return expireIfDue(quoteId)
                .then(decided)
                .flatMap(id -> detail(customerId, id))
                .switchIfEmpty(Mono.defer(() -> db.sql("SELECT status FROM quote_requests WHERE id = :id AND customer_id = :customer")
                        .bind("id", quoteId)
                        .bind("customer", customerId)
                        .map(row -> row.get("status", String.class))
                        .one()
                        .switchIfEmpty(Mono.error(() -> notFound()))
                        .flatMap(current -> Mono.error(new ApiProblem(HttpStatus.CONFLICT, "not_awaiting_decision",
                                "expired".equals(current)
                                        ? "This quote has expired. Ask us for a fresh price."
                                        : "This quote is not waiting for your decision.")))));
    }

    /** A sent price past its expiry date becomes expired the first time anyone looks. */
    private Mono<Void> expireIfDue(UUID quoteId) {
        return db.sql("""
                UPDATE quote_requests SET status = 'expired', updated_at = now()
                WHERE id = :id AND status = 'awaiting-approval' AND expires_at IS NOT NULL AND expires_at <= now()
                """)
                .bind("id", quoteId)
                .then();
    }

    ObjectNode summary(Readable row) {
        ObjectNode out = json.createObjectNode();
        out.put("id", row.get("id", UUID.class).toString());
        out.put("reference", row.get("reference", String.class));
        out.put("createdAt", row.get("created_at", OffsetDateTime.class).toInstant().toString());
        long lines = row.get("line_count", Long.class);
        String first = row.get("first_product", String.class);
        out.put("productName", lines > 1 ? first + " and " + (lines - 1) + " more" : first);
        out.put("quantity", row.get("total_quantity", Long.class));
        JsonNode pricing = JsonNodes.column(json, row, "pricing");
        out.set("total", pricing.isObject() && pricing.has("total") ? pricing.get("total") : NullNode.getInstance());
        out.put("status", row.get("status", String.class));
        OffsetDateTime expires = row.get("expires_at", OffsetDateTime.class);
        if (expires == null) {
            out.putNull("expiresAt");
        } else {
            out.put("expiresAt", expires.toInstant().toString());
        }
        return out;
    }

    private ObjectNode detailView(Readable row) {
        ObjectNode out = summary(row);
        out.set("request", JsonNodes.column(json, row, "request"));
        out.set("pricing", JsonNodes.column(json, row, "pricing"));
        out.put("staffNote", row.get("staff_note", String.class));
        out.set("designFileIds", JsonNodes.column(json, row, "design_ids"));
        out.put("awaitingCustomerApproval", "awaiting-approval".equals(row.get("status", String.class)));
        UUID order = row.get("converted_order_id", UUID.class);
        if (order == null) {
            out.putNull("convertedOrderId");
        } else {
            out.put("convertedOrderId", order.toString());
        }
        // The designFileIds list reads as strings, as the web app expects.
        ArrayNode ids = json.createArrayNode();
        out.get("designFileIds").forEach(id -> ids.add(id.asText()));
        out.set("designFileIds", ids);
        return out;
    }

    private static ApiProblem notFound() {
        return new ApiProblem(HttpStatus.NOT_FOUND, "not_found", "We could not find that quote.");
    }
}
