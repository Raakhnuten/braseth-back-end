package com.braseth.ecommerce.modules.order.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import io.r2dbc.postgresql.codec.Json;
import reactor.core.publisher.Mono;

/**
 * Turns an accepted quote into an order: the one place that happens, whether
 * the customer accepted in their account or staff recorded it for them.
 *
 * The order copies the quote's contact and its priced lines and totals, so a
 * later change to the quote never rewrites what was agreed. The caller runs
 * this inside its transaction together with the quote's status change.
 */
@Component
public class WebOrderFactory {

    public record Created(UUID id, String reference) {
    }

    private final DatabaseClient db;
    private final ObjectMapper json;

    WebOrderFactory(DatabaseClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public Mono<Created> fromQuote(UUID quoteId, UUID actorId) {
        return db.sql("SELECT * FROM quote_requests WHERE id = :id FOR UPDATE")
                .bind("id", quoteId)
                .map(row -> new Object[] {
                        row.get("pricing", Json.class),
                        row.get("customer_id", UUID.class),
                        row.get("full_name", String.class),
                        row.get("company_name", String.class),
                        row.get("email", String.class),
                        row.get("phone", String.class),
                        row.get("telegram", String.class),
                        row.get("preferred_contact_method", String.class),
                        row.get("converted_order_id", UUID.class) })
                .one()
                .switchIfEmpty(Mono.error(() -> new ApiProblem(HttpStatus.NOT_FOUND, "not_found", "We could not find that quote.")))
                .flatMap(q -> {
                    if (q[8] != null) {
                        return Mono.error(new ApiProblem(HttpStatus.CONFLICT, "already_ordered",
                                "This quote is already an order."));
                    }
                    if (q[0] == null) {
                        return Mono.error(new ApiProblem(HttpStatus.CONFLICT, "not_priced",
                                "Price the quote before it becomes an order."));
                    }
                    JsonNode pricing;
                    try {
                        pricing = json.readTree(((Json) q[0]).asString());
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                    String currency = pricing.path("total").path("currency").asText("USD");
                    // The priced lines, as agreed: plain amounts in the order's currency.
                    ArrayNode lines = json.createArrayNode();
                    for (JsonNode item : pricing.path("items")) {
                        lines.addObject()
                                .put("id", UUID.randomUUID().toString())
                                .put("description", item.path("description").asText(""))
                                .put("quantity", item.path("quantity").asInt())
                                .put("unitPrice", item.path("unitPrice").path("amount").decimalValue())
                                .put("lineTotal", item.path("lineTotal").path("amount").decimalValue());
                    }
                    GenericExecuteSpec order = db.sql("""
                            INSERT INTO orders (reference, quote_request_id, customer_id, full_name, company_name, email,
                                                phone, telegram, preferred_contact_method, currency, lines, subtotal,
                                                delivery_cost, discount, tax, total, updated_by)
                            VALUES ('SO-' || to_char(now(), 'YYYY') || '-' || lpad(nextval('order_reference_seq')::text, 5, '0'),
                                    :quote, :customer, :name, :company, :email, :phone, :telegram, :preferred, :currency,
                                    :lines, :subtotal, :delivery, :discount, :tax, :total, :actor)
                            RETURNING id, reference
                            """)
                            .bind("quote", quoteId)
                            .bind("name", q[2])
                            .bind("phone", q[5])
                            .bind("preferred", q[7])
                            .bind("currency", currency)
                            .bind("lines", Json.of(lines.toString()));
                    order = uuid(order, "customer", (UUID) q[1]);
                    order = uuid(order, "actor", actorId);
                    order = text(order, "company", (String) q[3]);
                    order = text(order, "email", (String) q[4]);
                    order = text(order, "telegram", (String) q[6]);
                    order = amount(order, "subtotal", pricing.get("subtotal"));
                    order = amount(order, "delivery", pricing.get("deliveryCost"));
                    order = amount(order, "discount", pricing.get("discount"));
                    order = amount(order, "tax", pricing.get("tax"));
                    order = amount(order, "total", pricing.get("total"));

                    return order.map(row -> new Created(row.get("id", UUID.class), row.get("reference", String.class)))
                            .one()
                            .flatMap(created -> {
                                // The first step on the timeline the customer sees.
                                GenericExecuteSpec event = uuid(db.sql("""
                                        INSERT INTO activity_log (kind, subject_id, status, actor_id)
                                        VALUES ('order-event', :order, 'approved', :actor)
                                        """).bind("order", created.id()), "actor", actorId);
                                return event.then()
                                        .then(db.sql("UPDATE quote_requests SET converted_order_id = :order WHERE id = :quote")
                                                .bind("order", created.id())
                                                .bind("quote", quoteId)
                                                .then())
                                        .thenReturn(created);
                            });
                });
    }

    private static GenericExecuteSpec amount(GenericExecuteSpec spec, String name, JsonNode money) {
        return money == null || money.isNull() || !money.has("amount")
                ? spec.bindNull(name, BigDecimal.class)
                : spec.bind(name, money.get("amount").decimalValue());
    }

    private static GenericExecuteSpec text(GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec uuid(GenericExecuteSpec spec, String name, UUID value) {
        return value == null ? spec.bindNull(name, UUID.class) : spec.bind(name, value);
    }
}
