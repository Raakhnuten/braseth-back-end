package com.braseth.ecommerce.modules.staff.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
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
import com.braseth.ecommerce.modules.design.service.DesignService;
import com.braseth.ecommerce.modules.order.service.WebOrderFactory;
import com.braseth.ecommerce.modules.order.service.WebOrderViews;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.PricingDraft;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.PricingLine;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.RecordedDecision;
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
 * The staff quote queue: price a request, send the price, record the
 * customer's answer (most are guests who reply by phone or Telegram), or
 * cancel. Every change is written to the activity log.
 *
 * Pricing is worked out here from the plain numbers staff enter; the screen
 * shows what comes back, so the total staff check is the total the customer
 * is sent. No tax is added: BRASETH has not set a rate, and a rate is not
 * this code's to invent.
 */
@Service
public class StaffQuotesService {

    /** BRASETH's clock, for "end of the expiry day". */
    static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Phnom_Penh");

    static final String SELECT = """
            SELECT q.*, %s,
                   o.reference AS order_reference
            FROM quote_requests q LEFT JOIN orders o ON o.id = q.converted_order_id
            """.formatted(WebOrderViews.QUOTE_LINE_TOTALS);

    private final DatabaseClient db;
    private final ObjectMapper json;
    private final DesignService designs;
    private final WebOrderFactory orders;
    private final StaffRecords records;
    private final TransactionalOperator transaction;

    StaffQuotesService(DatabaseClient db, ObjectMapper json, DesignService designs, WebOrderFactory orders,
            StaffRecords records, ReactiveTransactionManager transactionManager) {
        this.db = db;
        this.json = json;
        this.designs = designs;
        this.orders = orders;
        this.records = records;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    // ---- Reading ------------------------------------------------------------

    public Mono<ObjectNode> list(String status, String q, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String where = """
                WHERE (CAST(:status AS text) IS NULL OR q.status = :status)
                  AND (CAST(:q AS text) IS NULL
                       OR strpos(lower(q.reference), lower(:q)) > 0
                       OR strpos(lower(q.full_name), lower(:q)) > 0
                       OR strpos(lower(coalesce(q.company_name, '')), lower(:q)) > 0
                       OR strpos(regexp_replace(q.phone, '[^0-9]', '', 'g'), regexp_replace(:q, '[^0-9]', '', 'g')) > 0
                          AND regexp_replace(:q, '[^0-9]', '', 'g') <> '')
                """;
        GenericExecuteSpec rows = filters(db.sql(SELECT + where + " ORDER BY q.created_at DESC LIMIT :limit OFFSET :offset"), status, q)
                .bind("limit", safeSize)
                .bind("offset", (safePage - 1) * safeSize);
        GenericExecuteSpec count = filters(db.sql("SELECT count(*) AS n FROM quote_requests q " + where), status, q);
        return expireDue()
                .then(Mono.zip(rows.map(row -> summary(row)).all().collectList(),
                        count.map(row -> row.get("n", Long.class)).one()))
                .map(parts -> Paging.page(json, parts.getT1(), safePage, safeSize, parts.getT2()));
    }

    public Mono<ObjectNode> detail(UUID id) {
        return expireDue()
                .then(db.sql(SELECT + " WHERE q.id = :id").bind("id", id).map(row -> detailView(row)).one())
                .switchIfEmpty(Mono.error(() -> notFound()))
                .flatMap(out -> Mono.zip(lines(id), designs.forQuote(id)).map(parts -> {
                    out.set("requestedLines", parts.getT1());
                    ArrayNode files = out.putArray("designFiles");
                    parts.getT2().forEach(file -> files.add(json.valueToTree(file)));
                    return out;
                }));
    }

    /** The oldest requests still waiting for a price, for the overview. */
    public Mono<List<ObjectNode>> oldestUnpriced(int limit) {
        return db.sql(SELECT + " WHERE q.status = 'pending-review' ORDER BY q.created_at LIMIT :limit")
                .bind("limit", limit)
                .map(row -> summary(row))
                .all()
                .collectList();
    }

    // ---- Changing -----------------------------------------------------------

    public Mono<ObjectNode> price(Staff actor, UUID id, PricingDraft draft) {
        ObjectNode pricing;
        try {
            pricing = calculate(draft);
        } catch (ApiProblem problem) {
            return Mono.error(problem);
        }
        String staffNote = draft.staffNote() == null || draft.staffNote().isBlank() ? null : draft.staffNote().trim();
        GenericExecuteSpec spec = db.sql("""
                UPDATE quote_requests SET pricing = :pricing, staff_note = :note, has_unsent_pricing = true,
                       expires_at = CASE WHEN CAST(:expires AS date) IS NULL THEN NULL
                                         ELSE ((CAST(:expires AS date) + 1)::timestamp - interval '1 second') AT TIME ZONE 'Asia/Phnom_Penh' END,
                       updated_at = now(), updated_by = :actor
                WHERE id = :id AND status IN ('pending-review', 'quoted', 'awaiting-approval', 'expired', 'rejected')
                RETURNING reference
                """)
                .bind("pricing", Json.of(pricing.toString()))
                .bind("actor", actor.id())
                .bind("id", id);
        spec = staffNote == null ? spec.bindNull("note", String.class) : spec.bind("note", staffNote);
        spec = draft.expiresOn() == null ? spec.bindNull("expires", LocalDate.class) : spec.bind("expires", draft.expiresOn());
        return spec.map(row -> row.get("reference", String.class))
                .one()
                .switchIfEmpty(Mono.defer(() -> refuse(id, "This quote can no longer be priced.")))
                .flatMap(reference -> records.activity(actor, "quote", id, reference,
                        "Priced at " + format(pricing.get("total"))))
                .as(transaction::transactional)
                .then(detail(id));
    }

    /** Sends the saved price: the customer can now accept or decline it. */
    public Mono<ObjectNode> send(Staff actor, UUID id) {
        return db.sql("""
                UPDATE quote_requests SET status = 'awaiting-approval', pricing_sent_at = now(), has_unsent_pricing = false,
                       updated_at = now(), updated_by = :actor
                WHERE id = :id AND pricing IS NOT NULL
                  AND status IN ('pending-review', 'quoted', 'awaiting-approval', 'expired', 'rejected')
                  AND (expires_at IS NULL OR expires_at > now())
                RETURNING reference, customer_id, pricing
                """)
                .bind("actor", actor.id())
                .bind("id", id)
                .map(row -> new Object[] { row.get("reference", String.class), row.get("customer_id", UUID.class),
                        row.get("pricing", Json.class).asString() })
                .one()
                .switchIfEmpty(Mono.defer(() -> db.sql("SELECT status, pricing IS NULL AS unpriced, expires_at <= now() AS lapsed FROM quote_requests WHERE id = :id")
                        .bind("id", id)
                        .map(row -> new Object[] { row.get("status", String.class), row.get("unpriced", Boolean.class),
                                row.get("lapsed", Boolean.class) })
                        .one()
                        .switchIfEmpty(Mono.error(() -> notFound()))
                        .flatMap(state -> Mono.error(new ApiProblem(HttpStatus.CONFLICT, "cannot_send",
                                Boolean.TRUE.equals(state[1]) ? "Save a price before sending it."
                                        : Boolean.TRUE.equals(state[2]) ? "The expiry date has passed. Set a new one first."
                                                : "This quote can no longer be sent.")))))
                .flatMap(sent -> {
                    String reference = (String) sent[0];
                    String total = format(JsonNodes.parse(json, (String) sent[2]).get("total"));
                    return records.notifyCustomer((UUID) sent[1], "quote-updated", "/customer/quotes/" + id,
                                    "Your price is ready", reference + " · " + total)
                            .then(records.activity(actor, "quote", id, reference, "Sent the price (" + total + ")"));
                })
                .as(transaction::transactional)
                .then(detail(id));
    }

    /**
     * The customer's answer as staff heard it. Accepting makes the order;
     * declining keeps their reason. Allowed before the price was formally
     * sent too: a guest often agrees on the phone while staff are pricing.
     */
    public Mono<ObjectNode> recordDecision(Staff actor, UUID id, RecordedDecision decision) {
        String note = decision.note() == null || decision.note().isBlank() ? null : decision.note().trim();
        GenericExecuteSpec spec = db.sql("""
                UPDATE quote_requests SET status = :status, decided_at = now(), decision_note = :note,
                       has_unsent_pricing = false, updated_at = now(), updated_by = :actor
                WHERE id = :id AND status IN ('pending-review', 'quoted', 'awaiting-approval', 'expired')
                  AND (NOT :accepted OR pricing IS NOT NULL)
                RETURNING reference
                """)
                .bind("status", decision.accepted() ? "approved" : "rejected")
                .bind("accepted", decision.accepted())
                .bind("actor", actor.id())
                .bind("id", id);
        spec = note == null ? spec.bindNull("note", String.class) : spec.bind("note", note);
        String channel = switch (decision.channel()) {
            case "in-person" -> "in person";
            default -> "by " + decision.channel();
        };
        return spec.map(row -> row.get("reference", String.class))
                .one()
                .switchIfEmpty(Mono.defer(() -> db.sql("SELECT status, pricing IS NULL AS unpriced FROM quote_requests WHERE id = :id")
                        .bind("id", id)
                        .map(row -> new Object[] { row.get("status", String.class), row.get("unpriced", Boolean.class) })
                        .one()
                        .switchIfEmpty(Mono.error(() -> notFound()))
                        .flatMap(state -> Mono.error(new ApiProblem(HttpStatus.CONFLICT, "cannot_decide",
                                decision.accepted() && Boolean.TRUE.equals(state[1])
                                        ? "Price the quote before recording that the customer accepted."
                                        : "This quote has already been decided or cancelled.")))))
                .flatMap(reference -> decision.accepted()
                        ? orders.fromQuote(id, actor.id())
                                .flatMap(order -> records.activity(actor, "quote", id, reference,
                                        "Customer accepted " + channel + " — order " + order.reference())
                                        .then(records.activity(actor, "order", order.id(), order.reference(),
                                                "Created from " + reference)))
                        : records.activity(actor, "quote", id, reference,
                                "Customer declined " + channel + (note == null ? "" : ": " + note)))
                .as(transaction::transactional)
                .then(detail(id));
    }

    public Mono<ObjectNode> cancel(Staff actor, UUID id, String reason) {
        return db.sql("""
                UPDATE quote_requests SET status = 'cancelled', has_unsent_pricing = false, updated_at = now(), updated_by = :actor
                WHERE id = :id AND status NOT IN ('approved', 'cancelled')
                RETURNING reference
                """)
                .bind("actor", actor.id())
                .bind("id", id)
                .map(row -> row.get("reference", String.class))
                .one()
                .switchIfEmpty(Mono.defer(() -> refuse(id, "An accepted or cancelled quote cannot be cancelled.")))
                .flatMap(reference -> records.activity(actor, "quote", id, reference,
                        reason == null || reason.isBlank() ? "Cancelled" : "Cancelled: " + reason.trim()))
                .as(transaction::transactional)
                .then(detail(id));
    }

    // ---- Pricing ------------------------------------------------------------

    /** QuotePricing from the draft: lines, subtotal, discount and total, rounded to cents. */
    ObjectNode calculate(PricingDraft draft) {
        String currency = draft.currency();
        BigDecimal subtotal = BigDecimal.ZERO;
        ObjectNode out = json.createObjectNode();
        ArrayNode items = out.putArray("items");
        for (int i = 0; i < draft.items().size(); i++) {
            PricingLine line = draft.items().get(i);
            BigDecimal unit = round(line.unitPrice());
            BigDecimal lineTotal = round(unit.multiply(BigDecimal.valueOf(line.quantity())));
            subtotal = subtotal.add(lineTotal);
            items.addObject()
                    .put("id", "line-" + (i + 1))
                    .put("description", line.description().trim())
                    .put("quantity", line.quantity())
                    .<ObjectNode>set("unitPrice", money(unit, currency))
                    .set("lineTotal", money(lineTotal, currency));
        }
        out.set("printingCost", optional(draft.printingCost(), currency));
        subtotal = subtotal.add(orZero(draft.printingCost()));
        ArrayNode additional = out.putArray("additionalCosts");
        for (var cost : draft.additionalCosts()) {
            BigDecimal amount = round(cost.amount());
            subtotal = subtotal.add(amount);
            additional.addObject().put("label", cost.label().trim()).set("amount", money(amount, currency));
        }
        out.set("deliveryCost", optional(draft.deliveryCost(), currency));
        subtotal = subtotal.add(orZero(draft.deliveryCost()));
        BigDecimal discount = orZero(draft.discount());
        if (discount.compareTo(subtotal) > 0) {
            throw ApiProblem.field("discount", "discount_too_large", "The discount is more than the quote.");
        }
        out.set("subtotal", money(subtotal, currency));
        out.set("discount", optional(draft.discount(), currency));
        // No tax rate is configured; BRASETH sets it, the code does not guess.
        out.putNull("tax");
        out.set("total", money(subtotal.subtract(discount), currency));
        return out;
    }

    private JsonNode optional(BigDecimal amount, String currency) {
        return amount == null || amount.signum() == 0 ? NullNode.getInstance() : money(round(amount), currency);
    }

    private ObjectNode money(BigDecimal amount, String currency) {
        ObjectNode out = json.createObjectNode();
        out.put("amount", amount);
        out.put("currency", currency);
        return out;
    }

    private static BigDecimal round(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal orZero(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : round(amount);
    }

    /** "$545.00", or "KHR 120,000.00" for riel. */
    static String format(JsonNode money) {
        if (money == null || money.isNull()) {
            return "no total";
        }
        BigDecimal amount = money.get("amount").decimalValue().setScale(2, RoundingMode.HALF_UP);
        String digits = String.format("%,.2f", amount);
        return "USD".equals(money.path("currency").asText()) ? "$" + digits : money.path("currency").asText() + " " + digits;
    }

    // ---- Views --------------------------------------------------------------

    /** AdminQuoteSummary. */
    ObjectNode summary(Readable row) {
        ObjectNode out = json.createObjectNode();
        out.put("id", row.get("id", UUID.class).toString());
        out.put("reference", row.get("reference", String.class));
        out.put("createdAt", row.get("created_at", OffsetDateTime.class).toInstant().toString());
        long lines = row.get("line_count", Long.class);
        String first = row.get("first_product", String.class);
        out.put("productName", first == null ? "" : lines > 1 ? first + " and " + (lines - 1) + " more" : first);
        out.put("quantity", row.get("total_quantity", Long.class));
        JsonNode pricing = JsonNodes.column(json, row, "pricing");
        out.set("total", pricing.isObject() && pricing.has("total") ? pricing.get("total") : NullNode.getInstance());
        out.put("status", row.get("status", String.class));
        instant(out, "expiresAt", row.get("expires_at", OffsetDateTime.class));
        UUID customer = row.get("customer_id", UUID.class);
        if (customer == null) {
            out.putNull("customerId");
        } else {
            out.put("customerId", customer.toString());
        }
        out.put("customerName", row.get("full_name", String.class));
        text(out, "companyName", row.get("company_name", String.class));
        out.put("phone", row.get("phone", String.class));
        out.put("purpose", row.get("purpose", String.class));
        return out;
    }

    /** AdminQuote, without the two lists that need their own queries. */
    private ObjectNode detailView(Readable row) {
        ObjectNode out = summary(row);
        out.set("request", JsonNodes.column(json, row, "request"));
        out.set("pricing", JsonNodes.column(json, row, "pricing"));
        text(out, "staffNote", row.get("staff_note", String.class));
        out.put("awaitingCustomerApproval", "awaiting-approval".equals(row.get("status", String.class)));
        UUID order = row.get("converted_order_id", UUID.class);
        if (order == null) {
            out.putNull("convertedOrderId");
        } else {
            out.put("convertedOrderId", order.toString());
        }
        text(out, "convertedOrderReference", row.get("order_reference", String.class));
        instant(out, "pricingSentAt", row.get("pricing_sent_at", OffsetDateTime.class));
        out.put("hasUnsentPricing", Boolean.TRUE.equals(row.get("has_unsent_pricing", Boolean.class)));
        text(out, "decisionNote", row.get("decision_note", String.class));
        JsonNode ids = JsonNodes.column(json, row, "request").path("design").path("designFileIds");
        out.set("designFileIds", ids.isArray() ? ids : json.createArrayNode());
        return out;
    }

    private Mono<ArrayNode> lines(UUID quoteId) {
        return db.sql("SELECT lines FROM quote_requests WHERE id = :id")
                .bind("id", quoteId)
                .map(row -> {
                    ArrayNode out = json.createArrayNode();
                    for (JsonNode stored : JsonNodes.column(json, row, "lines")) {
                        ObjectNode line = out.addObject();
                        text(line, "productId", stored.path("productId").asText(null));
                        line.put("productName", stored.path("productName").asText(""));
                        line.put("quantity", stored.path("quantity").asInt());
                        text(line, "printMethod", stored.path("printMethod").asText(null));
                    }
                    return out;
                })
                .one()
                .defaultIfEmpty(json.createArrayNode());
    }

    /** Sent prices past their expiry become expired before anyone reads the queue. */
    private Mono<Void> expireDue() {
        return db.sql("""
                UPDATE quote_requests SET status = 'expired', updated_at = now()
                WHERE status = 'awaiting-approval' AND expires_at IS NOT NULL AND expires_at <= now()
                """).then();
    }

    private <T> Mono<T> refuse(UUID id, String message) {
        return db.sql("SELECT 1 AS found FROM quote_requests WHERE id = :id")
                .bind("id", id)
                .map(row -> true)
                .one()
                .switchIfEmpty(Mono.error(() -> notFound()))
                .flatMap(found -> Mono.error(new ApiProblem(HttpStatus.CONFLICT, "quote_closed", message)));
    }

    private static GenericExecuteSpec filters(GenericExecuteSpec spec, String status, String q) {
        spec = status == null || status.isBlank() ? spec.bindNull("status", String.class) : spec.bind("status", status);
        return q == null || q.isBlank() ? spec.bindNull("q", String.class) : spec.bind("q", q.trim());
    }

    private static void text(ObjectNode out, String field, String value) {
        if (value == null || value.isBlank()) {
            out.putNull(field);
        } else {
            out.put(field, value);
        }
    }

    private static void instant(ObjectNode out, String field, OffsetDateTime value) {
        if (value == null) {
            out.putNull(field);
        } else {
            out.put(field, value.toInstant().toString());
        }
    }

    private static ApiProblem notFound() {
        return new ApiProblem(HttpStatus.NOT_FOUND, "not_found", "We could not find that quote.");
    }
}
