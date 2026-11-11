package com.braseth.ecommerce.modules.order.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import com.braseth.ecommerce.common.util.JsonNodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.r2dbc.spi.Readable;
import reactor.core.publisher.Mono;

/**
 * Web orders as the web app reads them — the customer's Order and the staff
 * AdminOrder share these pieces. Amounts paid and due, and the payment
 * status, are worked out here from the recorded payments; nothing is stored
 * twice.
 */
@Component
public class WebOrderViews {

    /** Stages after which an order no longer counts as open. */
    public static final Set<String> CLOSED = Set.of("delivered", "completed", "cancelled");

    /** Customer-facing labels for each stage, used on the timeline. */
    private static final Map<String, String> LABEL = Map.of(
            "approved", "Order confirmed",
            "payment-pending", "Payment required",
            "in-production", "Printing started",
            "quality-control", "Quality check",
            "ready", "Ready for pickup or delivery",
            "shipping", "On its way",
            "delivered", "Delivered",
            "completed", "Completed",
            "cancelled", "Order cancelled");

    /** The customer-facing label for a stage, as the timeline shows it. */
    public static String label(String status) {
        return LABEL.getOrDefault(status, "Order updated");
    }

    /** Orders with what a list needs: the requested product, quantity and payments so far. */
    /**
     * What a list shows of a quote's requested lines (quote_requests.lines,
     * aliased {@code q}): the first product, how many lines and how many pieces.
     */
    public static final String QUOTE_LINE_TOTALS = """
            q.lines -> 0 ->> 'productName' AS first_product,
            jsonb_array_length(q.lines)::bigint AS line_count,
            (SELECT coalesce(sum((l ->> 'quantity')::int), 0) FROM jsonb_array_elements(q.lines) l)::bigint AS total_quantity
            """;

    public static final String SELECT = """
            SELECT o.*, q.reference AS quote_reference, q.design_file_ids, %s,
                   (SELECT coalesce(sum(p.amount), 0) FROM order_payments p WHERE p.order_id = o.id) AS paid
            FROM orders o JOIN quote_requests q ON q.id = o.quote_request_id
            """.formatted(QUOTE_LINE_TOTALS);

    private final DatabaseClient db;
    private final ObjectMapper json;

    WebOrderViews(DatabaseClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /** The customer's OrderSummary. */
    public ObjectNode summary(Readable row) {
        String currency = row.get("currency", String.class);
        ObjectNode out = json.createObjectNode();
        out.put("id", row.get("id", UUID.class).toString());
        out.put("reference", row.get("reference", String.class));
        out.put("placedAt", row.get("created_at", OffsetDateTime.class).toInstant().toString());
        long lines = row.get("line_count", Long.class);
        String first = row.get("first_product", String.class);
        out.put("productName", first == null ? "" : lines > 1 ? first + " and " + (lines - 1) + " more" : first);
        out.put("quantity", row.get("total_quantity", Long.class));
        out.set("total", money(row.get("total", BigDecimal.class), currency));
        out.put("status", row.get("status", String.class));
        return out;
    }

    /** The staff AdminOrderSummary: the customer's summary plus who, when due and what is owed. */
    public ObjectNode adminSummary(Readable row) {
        ObjectNode out = summary(row);
        UUID customer = row.get("customer_id", UUID.class);
        if (customer == null) {
            out.putNull("customerId");
        } else {
            out.put("customerId", customer.toString());
        }
        out.put("customerName", row.get("full_name", String.class));
        putText(out, "companyName", row.get("company_name", String.class));
        out.put("phone", row.get("phone", String.class));
        putDate(out, "dueDate", row.get("due_date", LocalDate.class));
        BigDecimal total = row.get("total", BigDecimal.class);
        BigDecimal paid = row.get("paid", BigDecimal.class);
        out.put("paymentStatus", paymentStatus(total, paid, row.get("status", String.class)));
        out.set("amountDue", total == null ? NullNode.getInstance()
                : money(total.subtract(paid).max(BigDecimal.ZERO), row.get("currency", String.class)));
        return out;
    }

    /** Detail fields every Order has, added to a summary built from the same row. */
    public Mono<ObjectNode> completeOrder(ObjectNode out, Readable row) {
        UUID orderId = row.get("id", UUID.class);
        String currency = row.get("currency", String.class);
        BigDecimal total = row.get("total", BigDecimal.class);
        BigDecimal paid = row.get("paid", BigDecimal.class);
        String status = row.get("status", String.class);

        out.put("quoteId", row.get("quote_request_id", UUID.class).toString());
        out.put("quoteReference", row.get("quote_reference", String.class));
        out.set("subtotal", money(row.get("subtotal", BigDecimal.class), currency));
        out.set("deliveryCost", money(row.get("delivery_cost", BigDecimal.class), currency));
        out.set("tax", money(row.get("tax", BigDecimal.class), currency));
        putText(out, "customerNote", row.get("customer_note", String.class));

        ObjectNode payment = out.putObject("payment");
        payment.put("status", paymentStatus(total, paid, status));
        payment.set("total", money(total, currency));
        payment.set("amountPaid", money(paid, currency));
        payment.set("amountDue", total == null ? NullNode.getInstance() : money(total.subtract(paid).max(BigDecimal.ZERO), currency));
        // How to pay is BRASETH's to write; nothing is invented here.
        payment.putNull("instructions");
        putDate(payment, "dueDate", row.get("due_date", LocalDate.class));

        ObjectNode delivery = out.putObject("delivery");
        putText(delivery, "method", row.get("delivery_method", String.class));
        putText(delivery, "address", row.get("delivery_address", String.class));
        putText(delivery, "contactName", row.get("delivery_contact_name", String.class));
        putText(delivery, "contactPhone", row.get("delivery_contact_phone", String.class));
        putDate(delivery, "estimatedDate", row.get("delivery_estimated_date", LocalDate.class));
        putText(delivery, "trackingReference", row.get("delivery_tracking", String.class));
        putText(delivery, "instructions", row.get("delivery_instructions", String.class));

        out.set("items", items(JsonNodes.column(json, row, "lines"), currency));
        ArrayNode designs = json.createArrayNode();
        UUID[] designIds = row.get("design_file_ids", UUID[].class);
        if (designIds != null) {
            Arrays.stream(designIds).sorted().forEach(id -> designs.add(id.toString()));
        }
        out.set("designFileIds", designs);
        return timeline(orderId).map(timeline -> {
            out.set("timeline", timeline);
            return out;
        });
    }

    /** The progress the customer sees: the order's 'order-event' entries in the activity log. */
    public Mono<ArrayNode> timeline(UUID orderId) {
        return db.sql("""
                SELECT id, status, body, at FROM activity_log
                WHERE kind = 'order-event' AND subject_id = :id ORDER BY at, id
                """)
                .bind("id", orderId)
                .map(row -> {
                    ObjectNode event = json.createObjectNode();
                    String status = row.get("status", String.class);
                    event.put("id", row.get("id", UUID.class).toString());
                    event.put("status", status);
                    event.put("occurredAt", row.get("at", OffsetDateTime.class).toInstant().toString());
                    event.put("label", label(status));
                    putText(event, "note", row.get("body", String.class));
                    return event;
                })
                .all()
                .collect(json::createArrayNode, ArrayNode::add);
    }

    /** The priced lines stored on the order (orders.lines). */
    private ArrayNode items(JsonNode lines, String currency) {
        ArrayNode out = json.createArrayNode();
        for (JsonNode line : lines) {
            ObjectNode item = out.addObject();
            item.put("id", line.path("id").asText());
            item.putNull("productId");
            item.put("productName", line.path("description").asText(""));
            item.put("sizeLabel", "");
            item.put("colorName", "");
            item.put("quantity", line.path("quantity").asInt());
            item.set("unitPrice", money(line.path("unitPrice").decimalValue(), currency));
            item.set("lineTotal", money(line.path("lineTotal").decimalValue(), currency));
        }
        return out;
    }

    /**
     * From what has been paid against the total; there is no stored payment
     * status to drift. Refunds are not recorded yet, so "refunded" is never
     * claimed — a cancelled order shows what was actually paid.
     */
    public static String paymentStatus(BigDecimal total, BigDecimal paid, String orderStatus) {
        if (total == null || total.signum() == 0) {
            return "not-required";
        }
        if (paid.compareTo(total) >= 0) {
            return "paid";
        }
        return paid.signum() > 0 ? "partially-paid" : "pending";
    }

    public JsonNode money(BigDecimal amount, String currency) {
        if (amount == null) {
            return NullNode.getInstance();
        }
        ObjectNode out = json.createObjectNode();
        out.put("amount", amount);
        out.put("currency", currency);
        return out;
    }

    static void putText(ObjectNode out, String field, String value) {
        if (value == null || value.isBlank()) {
            out.putNull(field);
        } else {
            out.put(field, value);
        }
    }

    static void putDate(ObjectNode out, String field, LocalDate value) {
        if (value == null) {
            out.putNull(field);
        } else {
            out.put(field, value.toString());
        }
    }
}
