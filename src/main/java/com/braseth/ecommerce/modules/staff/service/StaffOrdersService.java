package com.braseth.ecommerce.modules.staff.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.common.util.Paging;
import com.braseth.ecommerce.modules.design.service.DesignService;
import com.braseth.ecommerce.modules.order.service.WebOrderViews;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.OrderNote;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.OrderSchedule;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.OrderStatusChange;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.RecordedPayment;
import com.braseth.ecommerce.modules.staff.service.StaffGate.Staff;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.r2dbc.spi.Readable;
import reactor.core.publisher.Mono;

/**
 * Web orders from the staff side (AdminOrder): move them through
 * production, take payments, arrange delivery, keep internal notes. Every
 * write answers with the whole order, paid and due amounts worked out here,
 * and leaves a line in the activity log.
 */
@Service
public class StaffOrdersService {

    /** How staff read a stage in the activity log. */
    static final Map<String, String> STAGE = Map.of(
            "approved", "Confirmed",
            "payment-pending", "Waiting for payment",
            "in-production", "In production",
            "quality-control", "Quality check",
            "ready", "Ready",
            "shipping", "Out for delivery",
            "delivered", "Delivered",
            "completed", "Completed",
            "cancelled", "Cancelled");

    /** Payment status, worked out in SQL the same way as {@link WebOrderViews#paymentStatus}. */
    private static final String PAYMENT_STATUS = """
            CASE WHEN o.total IS NULL OR o.total = 0 THEN 'not-required'
                 WHEN o.paid >= o.total THEN 'paid'
                 WHEN o.paid > 0 THEN 'partially-paid'
                 ELSE 'pending' END
            """;

    private static final String FROM = "SELECT * FROM (" + WebOrderViews.SELECT + ") o ";

    private final DatabaseClient db;
    private final ObjectMapper json;
    private final WebOrderViews views;
    private final DesignService designs;
    private final StaffRecords records;
    private final TransactionalOperator transaction;

    StaffOrdersService(DatabaseClient db, ObjectMapper json, WebOrderViews views, DesignService designs,
            StaffRecords records, ReactiveTransactionManager transactionManager) {
        this.db = db;
        this.json = json;
        this.views = views;
        this.designs = designs;
        this.records = records;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    // ---- Reading ------------------------------------------------------------

    public Mono<ObjectNode> list(String status, String paymentStatus, String q, int page, int size) {
        int safePage = StaffViews.page(page);
        int safeSize = StaffViews.size(size);
        String where = """
                WHERE (CAST(:status AS text) IS NULL OR o.status = :status)
                  AND (CAST(:payment AS text) IS NULL OR %s = :payment)
                  AND (CAST(:q AS text) IS NULL
                       OR strpos(lower(o.reference), lower(:q)) > 0
                       OR strpos(lower(o.full_name), lower(:q)) > 0
                       OR strpos(lower(coalesce(o.company_name, '')), lower(:q)) > 0
                       OR strpos(regexp_replace(o.phone, '[^0-9]', '', 'g'), regexp_replace(:q, '[^0-9]', '', 'g')) > 0
                          AND regexp_replace(:q, '[^0-9]', '', 'g') <> '')
                """.formatted(PAYMENT_STATUS);
        GenericExecuteSpec rows = filters(db.sql(FROM + where + " ORDER BY o.created_at DESC LIMIT :limit OFFSET :offset"),
                status, paymentStatus, q)
                .bind("limit", safeSize)
                .bind("offset", (safePage - 1) * safeSize);
        GenericExecuteSpec count = filters(db.sql("SELECT count(*) AS n FROM (" + WebOrderViews.SELECT + ") o " + where),
                status, paymentStatus, q);
        return Mono.zip(rows.map(views::adminSummary).all().collectList(), count.map(row -> row.get("n", Long.class)).one())
                .map(parts -> Paging.page(json, parts.getT1(), safePage, safeSize, parts.getT2()));
    }

    public Mono<ObjectNode> detail(UUID id) {
        return db.sql(WebOrderViews.SELECT + " WHERE o.id = :id")
                .bind("id", id)
                .map(row -> {
                    ObjectNode out = views.adminSummary(row);
                    ObjectNode contact = out.putObject("contact");
                    contact.put("fullName", row.get("full_name", String.class));
                    putIf(contact, "companyName", row.get("company_name", String.class));
                    putIf(contact, "email", row.get("email", String.class));
                    contact.put("phone", row.get("phone", String.class));
                    putIf(contact, "telegram", row.get("telegram", String.class));
                    contact.put("preferredContactMethod", row.get("preferred_contact_method", String.class));
                    return views.completeOrder(out, row)
                            .zipWith(Mono.just(row.get("quote_request_id", UUID.class)));
                })
                .one()
                .flatMap(order -> order)
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("order")))
                .flatMap(parts -> Mono.zip(designs.forQuote(parts.getT2()), payments(id), notes(id)).map(more -> {
                    ObjectNode out = parts.getT1();
                    ArrayNode files = out.putArray("designFiles");
                    more.getT1().forEach(file -> files.add(json.valueToTree(file)));
                    out.set("payments", more.getT2());
                    out.set("internalNotes", more.getT3());
                    return out;
                }));
    }

    private Mono<ArrayNode> payments(UUID orderId) {
        return db.sql("""
                SELECT p.*, o.currency, u.name AS recorded_by_name
                FROM order_payments p JOIN orders o ON o.id = p.order_id
                LEFT JOIN users u ON u.id = p.recorded_by
                WHERE p.order_id = :id ORDER BY p.received_on, p.recorded_at
                """)
                .bind("id", orderId)
                .map(row -> {
                    ObjectNode out = json.createObjectNode();
                    out.put("id", row.get("id", UUID.class).toString());
                    out.set("amount", views.money(row.get("amount", BigDecimal.class), row.get("currency", String.class)));
                    out.put("method", row.get("method", String.class));
                    out.put("receivedOn", row.get("received_on", LocalDate.class).toString());
                    StaffViews.text(out, "reference", row.get("reference", String.class));
                    String by = row.get("recorded_by_name", String.class);
                    out.put("recordedBy", by == null ? "Former staff" : by);
                    return out;
                })
                .all()
                .collect(json::createArrayNode, ArrayNode::add);
    }

    private Mono<ArrayNode> notes(UUID orderId) {
        return db.sql("""
                SELECT n.id, n.body, n.at, u.name AS author_name FROM activity_log n LEFT JOIN users u ON u.id = n.actor_id
                WHERE n.kind = 'order-note' AND n.subject_id = :id ORDER BY n.at DESC
                """)
                .bind("id", orderId)
                .map(row -> note(json, row))
                .all()
                .collect(json::createArrayNode, ArrayNode::add);
    }

    /** InternalNote, shared with customer notes. */
    static ObjectNode note(ObjectMapper json, Readable row) {
        ObjectNode out = json.createObjectNode();
        out.put("id", row.get("id", UUID.class).toString());
        out.put("body", row.get("body", String.class));
        out.put("createdAt", row.get("at", OffsetDateTime.class).toInstant().toString());
        String author = row.get("author_name", String.class);
        out.put("authorName", author == null ? "Former staff" : author);
        return out;
    }

    // ---- Changing -----------------------------------------------------------

    /**
     * Moves the order to a new stage and adds the timeline event the customer
     * sees. A cancelled or completed order stays where it is.
     */
    public Mono<ObjectNode> changeStatus(Staff actor, UUID id, OrderStatusChange change) {
        String note = blankToNull(change.note());
        return locked(id)
                .flatMap(order -> {
                    if (order.status().equals(change.status())) {
                        return Mono.error(conflict("same_status", "The order is already at this stage."));
                    }
                    if (order.status().equals("cancelled") || order.status().equals("completed")) {
                        return Mono.error(conflict("order_closed", "A " + order.status() + " order cannot be moved."));
                    }
                    GenericExecuteSpec event = db.sql("""
                            INSERT INTO activity_log (kind, subject_id, status, body, actor_id, actor_name)
                            VALUES ('order-event', :id, :status, :note, :actor, :name)
                            """)
                            .bind("name", actor.name())
                            .bind("id", id)
                            .bind("status", change.status())
                            .bind("actor", actor.id());
                    event = StaffViews.bind(event, "note", note, String.class);
                    Mono<Void> notify = change.notifyCustomer()
                            ? records.notifyCustomer(order.customerId(), "order-status", "/customer/orders/" + id,
                                    WebOrderViews.label(change.status()), order.reference()
                                            + (note == null ? "" : " · " + note))
                            : Mono.empty();
                    return db.sql("UPDATE orders SET status = :status, updated_at = now(), updated_by = :actor WHERE id = :id")
                            .bind("status", change.status())
                            .bind("actor", actor.id())
                            .bind("id", id)
                            .then()
                            .then(event.then())
                            .then(notify)
                            .then(records.activity(actor, "order", id, order.reference(),
                                    "Moved to " + STAGE.getOrDefault(change.status(), change.status())
                                            + (note == null ? "" : ": " + note)));
                })
                .as(transaction::transactional)
                .then(Mono.defer(() -> detail(id)));
    }

    /** A payment as staff received it. More than is still due is refused. */
    public Mono<ObjectNode> recordPayment(Staff actor, UUID id, RecordedPayment payment) {
        BigDecimal amount = payment.amount().setScale(2, RoundingMode.HALF_UP);
        String reference = blankToNull(payment.reference());
        return locked(id)
                .flatMap(order -> {
                    if (order.status().equals("cancelled")) {
                        return Mono.error(conflict("order_closed", "Payments cannot be recorded on a cancelled order."));
                    }
                    if (order.total() != null && amount.compareTo(order.total().subtract(order.paid())) > 0) {
                        BigDecimal due = order.total().subtract(order.paid()).max(BigDecimal.ZERO);
                        return Mono.error(ApiProblem.field("amount", "overpayment",
                                "This is more than is still due (" + money(due, order.currency()) + ")."));
                    }
                    GenericExecuteSpec insert = db.sql("""
                            INSERT INTO order_payments (order_id, amount, method, received_on, reference, recorded_by)
                            VALUES (:id, :amount, :method, :received, :reference, :actor)
                            """)
                            .bind("id", id)
                            .bind("amount", amount)
                            .bind("method", payment.method().trim())
                            .bind("received", payment.receivedOn())
                            .bind("actor", actor.id());
                    insert = StaffViews.bind(insert, "reference", reference, String.class);
                    String text = money(amount, order.currency()) + " by " + payment.method().trim();
                    return insert.then()
                            .then(db.sql("UPDATE orders SET updated_at = now(), updated_by = :actor WHERE id = :id")
                                    .bind("actor", actor.id())
                                    .bind("id", id)
                                    .then())
                            .then(records.notifyCustomer(order.customerId(), "payment-status", "/customer/orders/" + id,
                                    "Payment received", order.reference() + " · " + money(amount, order.currency())))
                            .then(records.activity(actor, "order", id, order.reference(), "Recorded payment of " + text));
                })
                .as(transaction::transactional)
                .then(Mono.defer(() -> detail(id)));
    }

    public Mono<ObjectNode> updateSchedule(Staff actor, UUID id, OrderSchedule update) {
        OrderSchedule.Delivery delivery = update.delivery();
        GenericExecuteSpec spec = db.sql("""
                UPDATE orders SET due_date = :due, delivery_method = :method, delivery_address = :address,
                       delivery_contact_name = :contact, delivery_contact_phone = :phone,
                       delivery_estimated_date = :estimated, delivery_tracking = :tracking,
                       delivery_instructions = :instructions, updated_at = now(), updated_by = :actor
                WHERE id = :id
                RETURNING reference
                """)
                .bind("actor", actor.id())
                .bind("id", id);
        spec = StaffViews.bind(spec, "due", update.dueDate(), LocalDate.class);
        spec = StaffViews.bind(spec, "method", delivery == null ? null : delivery.method(), String.class);
        spec = StaffViews.bind(spec, "address", delivery == null ? null : delivery.address(), String.class);
        spec = StaffViews.bind(spec, "contact", delivery == null ? null : delivery.contactName(), String.class);
        spec = StaffViews.bind(spec, "phone", delivery == null ? null : delivery.contactPhone(), String.class);
        spec = StaffViews.bind(spec, "estimated", delivery == null ? null : delivery.estimatedDate(), LocalDate.class);
        spec = StaffViews.bind(spec, "tracking", delivery == null ? null : delivery.trackingReference(), String.class);
        spec = StaffViews.bind(spec, "instructions", delivery == null ? null : delivery.instructions(), String.class);
        String summary = update.dueDate() == null ? "Updated the schedule and delivery"
                : "Set the due date to " + update.dueDate() + " and updated delivery";
        return spec.map(row -> row.get("reference", String.class))
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("order")))
                .flatMap(reference -> records.activity(actor, "order", id, reference, summary))
                .as(transaction::transactional)
                .then(Mono.defer(() -> detail(id)));
    }

    public Mono<ObjectNode> addNote(Staff actor, UUID id, OrderNote note) {
        return db.sql("""
                WITH n AS (
                    INSERT INTO activity_log (kind, subject_id, body, actor_id, actor_name)
                    SELECT 'order-note', o.id, :body, :actor, :name FROM orders o WHERE o.id = :id
                    RETURNING subject_id
                )
                SELECT o.reference FROM orders o JOIN n ON n.subject_id = o.id
                """)
                .bind("body", note.body().trim())
                .bind("actor", actor.id())
                .bind("name", actor.name())
                .bind("id", id)
                .map(row -> row.get("reference", String.class))
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("order")))
                .flatMap(reference -> records.activity(actor, "order", id, reference, "Added an internal note"))
                .as(transaction::transactional)
                .then(Mono.defer(() -> detail(id)));
    }

    // ---- Helpers ------------------------------------------------------------

    private record Locked(String reference, String status, UUID customerId, String currency, BigDecimal total,
            BigDecimal paid) {
    }

    /** The order, locked for this transaction so two changes cannot interleave. */
    private Mono<Locked> locked(UUID id) {
        return db.sql("""
                SELECT o.reference, o.status, o.customer_id, o.currency, o.total,
                       (SELECT coalesce(sum(p.amount), 0) FROM order_payments p WHERE p.order_id = o.id) AS paid
                FROM orders o WHERE o.id = :id FOR UPDATE
                """)
                .bind("id", id)
                .map(row -> new Locked(row.get("reference", String.class), row.get("status", String.class),
                        row.get("customer_id", UUID.class), row.get("currency", String.class),
                        row.get("total", BigDecimal.class), row.get("paid", BigDecimal.class)))
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("order")));
    }

    /** "$545.00", or "KHR 120,000.00" for riel. */
    static String money(BigDecimal amount, String currency) {
        String digits = String.format("%,.2f", amount.setScale(2, RoundingMode.HALF_UP));
        return "USD".equals(currency) ? "$" + digits : currency + " " + digits;
    }

    private static GenericExecuteSpec filters(GenericExecuteSpec spec, String status, String payment, String q) {
        spec = StaffViews.bind(spec, "status", status, String.class);
        spec = StaffViews.bind(spec, "payment", payment, String.class);
        return StaffViews.bind(spec, "q", q, String.class);
    }

    private static void putIf(ObjectNode out, String field, String value) {
        if (value != null && !value.isBlank()) {
            out.put(field, value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApiProblem conflict(String code, String message) {
        return new ApiProblem(HttpStatus.CONFLICT, code, message);
    }
}
