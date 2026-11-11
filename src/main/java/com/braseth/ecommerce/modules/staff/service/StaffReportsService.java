package com.braseth.ecommerce.modules.staff.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.common.util.Csv;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Mono;

/**
 * Sales figures for a range of whole months (SalesReport), every sum worked
 * out here. The reporting currency is USD: orders priced in riel are left out
 * rather than converted, since an exchange rate is BRASETH's to set. Months
 * are Phnom Penh months. Cancelled orders never count as sales.
 */
@Service
public class StaffReportsService {

    private static final String CURRENCY = "USD";

    private static final int MAX_MONTHS = 36;

    /** Payments so far for each order. */
    private static final String PAID = "(SELECT coalesce(sum(p.amount), 0) FROM order_payments p WHERE p.order_id = o.id)";

    /** An order's placing time as a Phnom Penh date. */
    private static final String PLACED = "(o.created_at AT TIME ZONE 'Asia/Phnom_Penh')::date";

    private final DatabaseClient db;
    private final ObjectMapper json;

    StaffReportsService(DatabaseClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /** The months asked for; by default the twelve up to this one. */
    public record Range(YearMonth from, YearMonth to) {

        public LocalDate first() {
            return from.atDay(1);
        }

        public LocalDate last() {
            return to.atEndOfMonth();
        }
    }

    public Range range(String from, String to) {
        YearMonth end = to == null || to.isBlank() ? YearMonth.now(StaffQuotesService.SHOP_ZONE) : month(to, "to");
        YearMonth start = from == null || from.isBlank() ? end.minusMonths(11) : month(from, "from");
        if (start.isAfter(end)) {
            throw ApiProblem.field("from", "invalid_range", "The first month must not be after the last.");
        }
        if (start.plusMonths(MAX_MONTHS - 1).isBefore(end)) {
            throw ApiProblem.field("from", "range_too_long", "Choose at most " + MAX_MONTHS + " months.");
        }
        return new Range(start, end);
    }

    public Mono<ObjectNode> sales(Range range) {
        return Mono.zip(totals(range), quotes(range), monthly(range), topProducts(range, 10), unpaid(50))
                .map(parts -> {
                    ObjectNode out = json.createObjectNode();
                    out.put("from", range.first().toString());
                    out.put("to", range.last().toString());
                    out.set("totals", parts.getT1());
                    out.set("quotes", parts.getT2());
                    out.putArray("monthly").addAll(parts.getT3());
                    out.putArray("topProducts").addAll(parts.getT4());
                    out.putArray("unpaid").addAll(parts.getT5());
                    return out;
                });
    }

    /** One section as CSV, spreadsheet-safe. */
    public Mono<String> export(Range range, String section) {
        return switch (section) {
            case "monthly" -> monthly(range).map(rows -> csv(List.of("month", "orders", "sales_usd"), rows,
                    row -> List.of(row.get("month").asText(), row.get("orders").asLong(), amount(row.get("sales")))));
            case "products" -> topProducts(range, 1000).map(rows -> csv(List.of("product", "quantity", "orders", "sales_usd"), rows,
                    row -> List.of(row.get("productName").asText(), row.get("quantity").asLong(), row.get("orders").asLong(),
                            amount(row.get("sales")))));
            case "unpaid" -> unpaid(10_000).map(rows -> csv(List.of("order", "customer", "amount_due_usd", "due_date"), rows,
                    row -> List.of(row.get("reference").asText(), row.get("customerName").asText(),
                            amount(row.get("amountDue")), row.get("dueDate").isNull() ? "" : row.get("dueDate").asText())));
            default -> Mono.error(ApiProblem.field("section", "invalid_section", "Choose monthly, products or unpaid."));
        };
    }

    // ---- Sections -----------------------------------------------------------

    private Mono<ObjectNode> totals(Range range) {
        return bindRange(db.sql("""
                SELECT
                  (SELECT coalesce(sum(o.total), 0) FROM orders o
                   WHERE o.currency = :currency AND o.status <> 'cancelled' AND %1$s BETWEEN :first AND :last) AS sales,
                  (SELECT count(*) FROM orders o
                   WHERE o.currency = :currency AND o.status <> 'cancelled' AND %1$s BETWEEN :first AND :last) AS orders,
                  (SELECT count(*) FROM orders o
                   WHERE o.currency = :currency AND o.status <> 'cancelled' AND o.total IS NOT NULL
                     AND %1$s BETWEEN :first AND :last) AS priced_orders,
                  (SELECT coalesce(sum(p.amount), 0) FROM order_payments p JOIN orders o ON o.id = p.order_id
                   WHERE o.currency = :currency AND p.received_on BETWEEN :first AND :last) AS received,
                  (SELECT coalesce(sum(greatest(o.total - %2$s, 0)), 0) FROM orders o
                   WHERE o.currency = :currency AND o.status <> 'cancelled' AND o.total IS NOT NULL) AS outstanding
                """.formatted(PLACED, PAID)), range)
                .map(row -> {
                    BigDecimal sales = row.get("sales", BigDecimal.class);
                    long priced = row.get("priced_orders", Long.class);
                    ObjectNode out = json.createObjectNode();
                    out.set("sales", money(sales));
                    out.set("received", money(row.get("received", BigDecimal.class)));
                    out.set("outstanding", money(row.get("outstanding", BigDecimal.class)));
                    out.put("orders", row.get("orders", Long.class));
                    out.set("averageOrder", priced == 0 ? NullNode.getInstance()
                            : money(sales.divide(BigDecimal.valueOf(priced), 2, RoundingMode.HALF_UP)));
                    return out;
                })
                .one();
    }

    /** Quotes received, prices sent and accepted in the period; conversion is accepted ÷ sent. */
    private Mono<ObjectNode> quotes(Range range) {
        // Quotes have no currency until priced, so only the dates are bound here.
        return db.sql("""
                SELECT
                  count(*) FILTER (WHERE (q.created_at AT TIME ZONE 'Asia/Phnom_Penh')::date BETWEEN :first AND :last) AS received,
                  count(*) FILTER (WHERE (q.pricing_sent_at AT TIME ZONE 'Asia/Phnom_Penh')::date BETWEEN :first AND :last) AS sent,
                  count(*) FILTER (WHERE q.status = 'approved'
                                     AND (q.decided_at AT TIME ZONE 'Asia/Phnom_Penh')::date BETWEEN :first AND :last) AS approved
                FROM quote_requests q
                """)
                .bind("first", range.first())
                .bind("last", range.last())
                .map(row -> {
                    long sent = row.get("sent", Long.class);
                    long approved = row.get("approved", Long.class);
                    ObjectNode out = json.createObjectNode();
                    out.put("received", row.get("received", Long.class));
                    out.put("sent", sent);
                    out.put("approved", approved);
                    if (sent == 0) {
                        out.putNull("conversionRate");
                    } else {
                        // A quote accepted on the phone before its price was sent can push this over 1.
                        out.put("conversionRate", Math.min(1.0, Math.round(approved * 1000.0 / sent) / 1000.0));
                    }
                    return out;
                })
                .one();
    }

    /** Every month in the range, empty ones included, oldest first. */
    private Mono<List<ObjectNode>> monthly(Range range) {
        return bindRange(db.sql("""
                SELECT to_char(m.month, 'YYYY-MM') AS month,
                       count(o.id) AS orders, coalesce(sum(o.total), 0) AS sales
                FROM generate_series(CAST(:first AS timestamp), CAST(:last AS timestamp), interval '1 month') AS m(month)
                LEFT JOIN orders o ON o.currency = :currency AND o.status <> 'cancelled'
                     AND date_trunc('month', CAST(%s AS timestamp)) = m.month
                GROUP BY m.month ORDER BY m.month
                """.formatted(PLACED)), range)
                .map(row -> {
                    ObjectNode out = json.createObjectNode();
                    out.put("month", row.get("month", String.class));
                    out.set("sales", money(row.get("sales", BigDecimal.class)));
                    out.put("orders", row.get("orders", Long.class));
                    return out;
                })
                .all()
                .collectList();
    }

    /** By priced line, as staff described it on the quote; largest sales first. */
    private Mono<List<ObjectNode>> topProducts(Range range, int limit) {
        return bindRange(db.sql("""
                SELECT l.description AS product, sum(l.quantity) AS quantity, count(DISTINCT o.id) AS orders,
                       sum(l."lineTotal") AS sales
                FROM orders o
                CROSS JOIN jsonb_to_recordset(o.lines) AS l(description text, quantity integer, "lineTotal" numeric)
                WHERE o.currency = :currency AND o.status <> 'cancelled' AND %s BETWEEN :first AND :last
                GROUP BY l.description
                ORDER BY sales DESC, quantity DESC, l.description
                LIMIT :limit
                """.formatted(PLACED)), range)
                .bind("limit", limit)
                .map(row -> {
                    ObjectNode out = json.createObjectNode();
                    out.put("productName", row.get("product", String.class));
                    out.put("quantity", row.get("quantity", Long.class));
                    out.put("orders", row.get("orders", Long.class));
                    out.set("sales", money(row.get("sales", BigDecimal.class)));
                    return out;
                })
                .all()
                .collectList();
    }

    /** Orders still owing money, whatever the period; largest balance first. */
    private Mono<List<ObjectNode>> unpaid(int limit) {
        return db.sql("""
                SELECT * FROM (
                    SELECT o.id, o.reference, o.full_name, o.due_date, o.total - %s AS due
                    FROM orders o
                    WHERE o.currency = :currency AND o.status <> 'cancelled' AND o.total IS NOT NULL
                ) t WHERE t.due > 0
                ORDER BY t.due DESC, t.due_date NULLS LAST
                LIMIT :limit
                """.formatted(PAID))
                .bind("currency", CURRENCY)
                .bind("limit", limit)
                .map(row -> {
                    ObjectNode out = json.createObjectNode();
                    out.put("orderId", row.get("id", UUID.class).toString());
                    out.put("reference", row.get("reference", String.class));
                    out.put("customerName", row.get("full_name", String.class));
                    out.set("amountDue", money(row.get("due", BigDecimal.class)));
                    StaffViews.date(out, "dueDate", row.get("due_date", LocalDate.class));
                    return out;
                })
                .all()
                .collectList();
    }

    // ---- Helpers ------------------------------------------------------------

    private GenericExecuteSpec bindRange(GenericExecuteSpec spec, Range range) {
        return spec.bind("currency", CURRENCY).bind("first", range.first()).bind("last", range.last());
    }

    private ObjectNode money(BigDecimal amount) {
        ObjectNode out = json.createObjectNode();
        out.put("amount", amount.setScale(2, RoundingMode.HALF_UP));
        out.put("currency", CURRENCY);
        return out;
    }

    private static String amount(JsonNode money) {
        return money.get("amount").decimalValue().setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private interface Row {
        List<Object> of(ObjectNode row);
    }

    private static String csv(List<Object> header, List<ObjectNode> rows, Row toRow) {
        List<List<Object>> lines = new ArrayList<>();
        lines.add(header);
        rows.forEach(row -> lines.add(toRow.of(row)));
        return Csv.of(lines);
    }

    private static YearMonth month(String value, String field) {
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiProblem.field(field, "invalid_month", "Give the month as YYYY-MM.");
        }
    }
}
