package com.braseth.ecommerce.modules.account.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.common.util.Paging;
import com.braseth.ecommerce.modules.order.service.WebOrderViews;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Mono;

/**
 * A customer's orders: the orders made from their accepted quotes. Every
 * query is scoped to the signed-in customer.
 */
@Service
public class MyOrdersService {

    private final DatabaseClient db;
    private final ObjectMapper json;
    private final WebOrderViews views;

    MyOrdersService(DatabaseClient db, ObjectMapper json, WebOrderViews views) {
        this.db = db;
        this.json = json;
        this.views = views;
    }

    public Mono<ObjectNode> list(UUID customerId, String status, String search, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 48);
        String where = """
                WHERE o.customer_id = :customer
                  AND (CAST(:status AS text) IS NULL OR o.status = :status)
                  AND (CAST(:search AS text) IS NULL
                       OR strpos(lower(o.reference), lower(:search)) > 0
                       OR EXISTS (SELECT 1 FROM quote_requests q, jsonb_array_elements(q.lines) l
                                  WHERE q.id = o.quote_request_id
                                    AND strpos(lower(l ->> 'productName'), lower(:search)) > 0))
                """;
        GenericExecuteSpec rows = bind(db.sql(WebOrderViews.SELECT + where
                + " ORDER BY o.created_at DESC LIMIT :limit OFFSET :offset"), customerId, status, search)
                .bind("limit", safeSize)
                .bind("offset", (safePage - 1) * safeSize);
        GenericExecuteSpec count = bind(db.sql("SELECT count(*) AS n FROM orders o " + where), customerId, status, search);
        return Mono.zip(rows.map(row -> views.summary(row)).all().collectList(),
                        count.map(row -> row.get("n", Long.class)).one())
                .map(parts -> Paging.page(json, parts.getT1(), safePage, safeSize, parts.getT2()));
    }

    public Mono<ObjectNode> detail(UUID customerId, UUID orderId) {
        return db.sql(WebOrderViews.SELECT + " WHERE o.id = :id AND o.customer_id = :customer")
                .bind("id", orderId)
                .bind("customer", customerId)
                .map(row -> views.completeOrder(views.summary(row), row))
                .one()
                .flatMap(order -> order)
                .switchIfEmpty(Mono.error(() -> notFound()));
    }

    public Mono<ArrayNode> tracking(UUID customerId, UUID orderId) {
        return db.sql("SELECT id FROM orders WHERE id = :id AND customer_id = :customer")
                .bind("id", orderId)
                .bind("customer", customerId)
                .map(row -> row.get("id", UUID.class))
                .one()
                .switchIfEmpty(Mono.error(() -> notFound()))
                .flatMap(views::timeline);
    }

    private static GenericExecuteSpec bind(GenericExecuteSpec spec, UUID customerId, String status, String search) {
        spec = spec.bind("customer", customerId);
        spec = status == null || status.isBlank() ? spec.bindNull("status", String.class) : spec.bind("status", status);
        return search == null || search.isBlank() ? spec.bindNull("search", String.class) : spec.bind("search", search.trim());
    }

    private static ApiProblem notFound() {
        return new ApiProblem(HttpStatus.NOT_FOUND, "not_found", "We could not find that order.");
    }
}
