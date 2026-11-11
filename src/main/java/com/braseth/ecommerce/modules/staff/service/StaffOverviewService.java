package com.braseth.ecommerce.modules.staff.service;

import java.time.LocalDate;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import com.braseth.ecommerce.modules.order.service.WebOrderViews;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Mono;

/**
 * The staff home screen (AdminOverview): what needs doing today, counted
 * here. Dates are BRASETH's dates in Phnom Penh, so "overdue" turns over at
 * the shop's midnight, not the server's.
 */
@Service
public class StaffOverviewService {

    private static final String OPEN = "o.status NOT IN ('delivered', 'completed', 'cancelled')";

    /** The stages an open order moves through, in the workshop's order. */
    private static final String[] OPEN_STAGES = {
            "approved", "payment-pending", "in-production", "quality-control", "ready", "shipping" };

    private final DatabaseClient db;
    private final ObjectMapper json;
    private final StaffQuotesService quotes;
    private final WebOrderViews orders;

    StaffOverviewService(DatabaseClient db, ObjectMapper json, StaffQuotesService quotes, WebOrderViews orders) {
        this.db = db;
        this.json = json;
        this.quotes = quotes;
        this.orders = orders;
    }

    public Mono<ObjectNode> overview() {
        LocalDate today = LocalDate.now(StaffQuotesService.SHOP_ZONE);
        Mono<long[]> counts = db.sql("""
                SELECT
                  (SELECT count(*) FROM quote_requests WHERE status = 'pending-review') AS needs_pricing,
                  (SELECT count(*) FROM quote_requests WHERE status = 'awaiting-approval'
                     AND (expires_at IS NULL OR expires_at > now())) AS waiting,
                  (SELECT count(*) FROM design_files WHERE status IN ('uploaded', 'under-review') AND deleted_at IS NULL) AS to_review,
                  (SELECT count(*) FROM orders o WHERE %1$s AND o.due_date < :today) AS overdue,
                  (SELECT count(*) FROM orders o WHERE %1$s AND o.due_date BETWEEN :today AND :week) AS due_week,
                  (SELECT count(*) FROM orders o WHERE o.status = 'payment-pending') AS awaiting_payment,
                  (SELECT count(*) FROM orders o WHERE o.status = 'ready') AS ready
                """.formatted(OPEN))
                .bind("today", today)
                .bind("week", today.plusDays(7))
                .map(row -> new long[] {
                        row.get("needs_pricing", Long.class), row.get("waiting", Long.class),
                        row.get("to_review", Long.class), row.get("overdue", Long.class),
                        row.get("due_week", Long.class), row.get("awaiting_payment", Long.class),
                        row.get("ready", Long.class) })
                .one();
        Mono<ArrayNode> nextDue = db.sql(WebOrderViews.SELECT + " WHERE " + OPEN
                + " ORDER BY o.due_date ASC NULLS LAST, o.created_at DESC LIMIT 5")
                .map(row -> orders.adminSummary(row))
                .all()
                .collect(json::createArrayNode, ArrayNode::add);

        // Open orders per production stage, every stage present so the chart's rows never move.
        Mono<ArrayNode> byStage = db.sql("""
                SELECT s.status, count(o.id) AS n
                FROM unnest(CAST(:stages AS text[])) WITH ORDINALITY AS s(status, position)
                LEFT JOIN orders o ON o.status = s.status
                GROUP BY s.status, s.position ORDER BY s.position
                """)
                .bind("stages", OPEN_STAGES)
                .map(row -> (JsonNode) json.createObjectNode()
                        .put("status", row.get("status", String.class))
                        .put("count", row.get("n", Long.class)))
                .all()
                .collect(json::createArrayNode, ArrayNode::add);
        // The last 30 days of quote work: asked for, priced and sent, accepted.
        Mono<ObjectNode> funnel = db.sql("""
                SELECT
                  count(*) FILTER (WHERE created_at >= now() - interval '30 days') AS received,
                  count(*) FILTER (WHERE pricing_sent_at >= now() - interval '30 days') AS sent,
                  count(*) FILTER (WHERE status = 'approved' AND decided_at >= now() - interval '30 days') AS approved
                FROM quote_requests
                """)
                .map(row -> json.createObjectNode()
                        .put("received", row.get("received", Long.class))
                        .put("sent", row.get("sent", Long.class))
                        .put("approved", row.get("approved", Long.class)))
                .one();

        return Mono.zip(counts, quotes.oldestUnpriced(5), nextDue, byStage, funnel).map(parts -> {
            long[] n = parts.getT1();
            ObjectNode out = json.createObjectNode();
            out.putObject("quotes").put("needsPricing", n[0]).put("waitingOnCustomer", n[1]);
            out.putObject("designs").put("toReview", n[2]);
            out.putObject("orders")
                    .put("overdue", n[3])
                    .put("dueThisWeek", n[4])
                    .put("awaitingPayment", n[5])
                    .put("readyForHandover", n[6]);
            out.putArray("oldestUnpriced").addAll(parts.getT2());
            out.set("nextDue", parts.getT3());
            ObjectNode charts = out.putObject("charts");
            charts.set("ordersByStage", parts.getT4());
            charts.set("quotesLast30Days", parts.getT5());
            return out;
        });
    }
}
