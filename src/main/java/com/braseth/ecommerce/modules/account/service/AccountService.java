package com.braseth.ecommerce.modules.account.service;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.common.util.JsonNodes;
import com.braseth.ecommerce.common.util.PhoneNumbers;
import com.braseth.ecommerce.modules.account.dto.AccountBodies.AddressBody;
import com.braseth.ecommerce.modules.account.dto.AccountBodies.CompanyBody;
import com.braseth.ecommerce.modules.account.dto.AccountBodies.ProfileBody;
import com.braseth.ecommerce.modules.account.dto.AccountBodies.SettingsBody;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.CustomerView;
import com.braseth.ecommerce.modules.auth.service.AuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.r2dbc.postgresql.codec.Json;
import reactor.core.publisher.Mono;

/**
 * The customer's own account: profile, company, notification preferences and
 * the dashboard. All of it is kept on the customer's users row.
 */
@Service
public class AccountService {

    private final DatabaseClient db;
    private final ObjectMapper json;
    private final AuthService auth;
    private final MyQuotesService quotes;
    private final MyOrdersService orders;
    private final TransactionalOperator transaction;

    AccountService(DatabaseClient db, ObjectMapper json, AuthService auth, MyQuotesService quotes,
            MyOrdersService orders, ReactiveTransactionManager transactionManager) {
        this.db = db;
        this.json = json;
        this.auth = auth;
        this.quotes = quotes;
        this.orders = orders;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    // ---- Profile ------------------------------------------------------------

    public Mono<CustomerView> profile(UUID userId) {
        return auth.me(userId);
    }

    public Mono<CustomerView> updateProfile(UUID userId, ProfileBody body) {
        String phone = PhoneNumbers.normalise(body.phone());
        if (phone == null) {
            return Mono.error(ApiProblem.field("phone", "invalid_phone",
                    "Enter a phone number such as 012 345 678 or +855 12 345 678."));
        }
        if ("telegram".equals(body.preferredContactMethod()) && blank(body.telegram())) {
            return Mono.error(ApiProblem.field("telegram", "telegram_required",
                    "Add your Telegram to be reached there."));
        }
        AddressBody address = body.address();
        return db.sql("SELECT 1 FROM users WHERE phone = :phone AND id <> :id AND deleted_at IS NULL LIMIT 1")
                .bind("phone", phone)
                .bind("id", userId)
                .map(row -> true)
                .one()
                .flatMap(taken -> Mono.<Void>error(ApiProblem.conflict("phone", "phone_taken",
                        "Another account already uses this phone number.")))
                .then(Mono.defer(() -> text(db.sql("""
                                UPDATE users SET name = :name, phone = :phone, telegram = :telegram,
                                    preferred_contact_method = :method, updated_at = now()
                                WHERE id = :id
                                """)
                                .bind("name", body.fullName().trim())
                                .bind("phone", phone)
                                .bind("method", body.preferredContactMethod())
                                .bind("id", userId),
                                "telegram", trimToNull(body.telegram()))
                        .then()
                        // The profile page does not edit the address; leave it as it is unless one is sent.
                        .then(address == null ? Mono.<Void>empty() : text(db.sql("""
                                UPDATE users SET
                                    address = :line, city = :city, province = :province,
                                    postal_code = :postal, country = :country
                                WHERE id = :id
                                """)
                                .bind("id", userId),
                                "line", joinLines(address.line1(), address.line2()),
                                "city", address.city().trim(),
                                "province", trimToNull(address.province()),
                                "postal", trimToNull(address.postalCode()),
                                "country", address.country().trim())
                                .then())))
                .as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> ApiProblem.conflict("phone", "phone_taken",
                        "Another account already uses this phone number."))
                .then(auth.me(userId));
    }

    // ---- Company ------------------------------------------------------------

    /**
     * The company record; null for an individual account. The name is
     * users.company_name, which decides whether the account reads as
     * corporate; the rest is users.company.
     */
    public Mono<JsonNode> company(UUID userId) {
        return db.sql("SELECT company_name, company FROM users WHERE id = :id")
                .bind("id", userId)
                .map(row -> {
                    String name = row.get("company_name", String.class);
                    if (blank(name)) {
                        return (JsonNode) NullNode.getInstance();
                    }
                    JsonNode stored = JsonNodes.column(json, row, "company");
                    ObjectNode out = json.createObjectNode();
                    out.put("id", userId.toString());
                    out.put("name", name);
                    out.set("address", stored.path("address").isObject() ? stored.get("address") : NullNode.getInstance());
                    for (String field : new String[] { "contactPersonName", "contactPersonRole", "contactEmail",
                            "contactPhone", "taxId", "industry", "website" }) {
                        putText(out, field, stored.path(field).asText(null));
                    }
                    return (JsonNode) out;
                })
                .one()
                .defaultIfEmpty(NullNode.getInstance());
    }

    /** Saving company details makes the account a corporate one. */
    public Mono<JsonNode> updateCompany(UUID userId, CompanyBody body) {
        ObjectNode company = json.createObjectNode();
        if (body.address() != null) {
            company.set("address", json.valueToTree(body.address()));
        }
        putIfText(company, "contactPersonName", body.contactPersonName());
        putIfText(company, "contactPersonRole", body.contactPersonRole());
        putIfText(company, "contactEmail", body.contactEmail());
        putIfText(company, "contactPhone", body.contactPhone());
        putIfText(company, "taxId", body.taxId());
        putIfText(company, "industry", body.industry());
        putIfText(company, "website", body.website());
        return db.sql("UPDATE users SET company_name = :name, company = :company, updated_at = now() WHERE id = :id")
                .bind("name", body.name().trim())
                .bind("company", Json.of(company.toString()))
                .bind("id", userId)
                .then()
                .then(company(userId));
    }

    // ---- Notification preferences -------------------------------------------

    public Mono<ObjectNode> settings(UUID userId) {
        return db.sql("""
                SELECT preferred_contact_method AS channel, notify_quote_updates AS quote_updates,
                       notify_order_updates AS order_updates, notify_design_updates AS design_updates,
                       marketing_opt_in AS marketing
                FROM users WHERE id = :id
                """)
                .bind("id", userId)
                .map(row -> {
                    ObjectNode out = json.createObjectNode();
                    out.put("channel", row.get("channel", String.class));
                    out.put("quoteUpdates", row.get("quote_updates", Boolean.class));
                    out.put("orderUpdates", row.get("order_updates", Boolean.class));
                    out.put("designUpdates", row.get("design_updates", Boolean.class));
                    out.put("marketing", row.get("marketing", Boolean.class));
                    return out;
                })
                .one();
    }

    public Mono<ObjectNode> updateSettings(UUID userId, SettingsBody body) {
        return db.sql("""
                UPDATE users SET preferred_contact_method = :channel, notify_quote_updates = :quotes,
                       notify_order_updates = :orders, notify_design_updates = :designs, marketing_opt_in = :marketing,
                       updated_at = now()
                WHERE id = :id
                """)
                .bind("channel", body.channel())
                .bind("quotes", body.quoteUpdates())
                .bind("orders", body.orderUpdates())
                .bind("designs", body.designUpdates())
                .bind("marketing", body.marketing())
                .bind("id", userId)
                .then()
                .then(settings(userId));
    }

    // ---- Dashboard ----------------------------------------------------------

    public Mono<ObjectNode> dashboard(UUID userId) {
        Mono<Long> activeQuotes = count("""
                SELECT count(*) AS n FROM quote_requests
                WHERE customer_id = :id AND status IN ('pending-review', 'quoted', 'awaiting-approval')
                """, userId);
        Mono<Long> activeOrders = count("""
                SELECT count(*) AS n FROM orders
                WHERE customer_id = :id AND status NOT IN ('delivered', 'completed', 'cancelled')
                """, userId);
        return Mono.zip(activeQuotes, activeOrders, quotes.list(userId, null, 1, 3), orders.list(userId, null, null, 1, 3),
                pendingActions(userId))
                .map(parts -> {
                    ObjectNode out = json.createObjectNode();
                    out.put("activeQuoteCount", parts.getT1());
                    out.put("activeOrderCount", parts.getT2());
                    out.set("recentQuotes", parts.getT3().get("items"));
                    out.set("recentOrders", parts.getT4().get("items"));
                    out.set("pendingActions", parts.getT5());
                    return out;
                });
    }

    /** What the customer should do next: decide a quote, replace artwork, pay. */
    private Mono<ArrayNode> pendingActions(UUID userId) {
        Mono<ArrayNode> decide = db.sql("""
                SELECT q.id, q.reference FROM quote_requests q
                WHERE q.customer_id = :id AND q.status = 'awaiting-approval'
                  AND (q.expires_at IS NULL OR q.expires_at > now())
                ORDER BY q.pricing_sent_at DESC NULLS LAST
                """)
                .bind("id", userId)
                .map(row -> action("quote-" + row.get("id", UUID.class), "approve-quote",
                        "Your price for " + row.get("reference", String.class) + " is ready",
                        "Review the quote and approve it, or tell us what to change.",
                        "/customer/quotes/" + row.get("id", UUID.class), "Review quote"))
                .all()
                .collect(json::createArrayNode, ArrayNode::add);
        Mono<ArrayNode> replace = db.sql("""
                SELECT id, original_name, review_note FROM design_files
                WHERE customer_id = :id AND status = 'rejected' AND deleted_at IS NULL
                ORDER BY reviewed_at DESC NULLS LAST
                """)
                .bind("id", userId)
                .map(row -> action("design-" + row.get("id", UUID.class), "replace-design",
                        "New file needed: " + row.get("original_name", String.class),
                        blank(row.get("review_note", String.class)) ? "We need a different version of this file."
                                : row.get("review_note", String.class),
                        "/customer/designs", "Upload a new file"))
                .all()
                .collect(json::createArrayNode, ArrayNode::add);
        Mono<ArrayNode> pay = db.sql("""
                SELECT o.id, o.reference FROM orders o
                WHERE o.customer_id = :id AND o.total > 0
                  AND o.status NOT IN ('delivered', 'completed', 'cancelled')
                  AND o.total > (SELECT coalesce(sum(p.amount), 0) FROM order_payments p WHERE p.order_id = o.id)
                ORDER BY o.created_at
                """)
                .bind("id", userId)
                .map(row -> action("payment-" + row.get("id", UUID.class), "make-payment",
                        "Payment due for " + row.get("reference", String.class),
                        "See what is due and how to pay.",
                        "/customer/orders/" + row.get("id", UUID.class), "View order"))
                .all()
                .collect(json::createArrayNode, ArrayNode::add);
        return Mono.zip(decide, replace, pay).map(parts -> {
            ArrayNode all = json.createArrayNode();
            all.addAll(parts.getT1());
            all.addAll(parts.getT2());
            all.addAll(parts.getT3());
            return all;
        });
    }

    private ObjectNode action(String id, String kind, String title, String description, String link, String label) {
        ObjectNode out = json.createObjectNode();
        out.put("id", id);
        out.put("kind", kind);
        out.put("title", title);
        out.put("description", description);
        out.put("link", link);
        out.put("actionLabel", label);
        return out;
    }

    // ---- Helpers ------------------------------------------------------------

    private Mono<Long> count(String sql, UUID userId) {
        return db.sql(sql).bind("id", userId).map(row -> row.get("n", Long.class)).one();
    }

    /** Binds name/value pairs, as NULL where the value is null. */
    private static GenericExecuteSpec text(GenericExecuteSpec spec, String... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            spec = pairs[i + 1] == null ? spec.bindNull(pairs[i], String.class) : spec.bind(pairs[i], pairs[i + 1]);
        }
        return spec;
    }

    /** users has one address column; a second line is kept on its own line. */
    private static String joinLines(String line1, String line2) {
        return blank(line2) ? line1.trim() : line1.trim() + "\n" + line2.trim();
    }

    private static void putText(ObjectNode out, String field, String value) {
        if (blank(value)) {
            out.putNull(field);
        } else {
            out.put(field, value);
        }
    }

    /** Only fields that say something are stored. */
    private static void putIfText(ObjectNode out, String field, String value) {
        if (!blank(value)) {
            out.put(field, value.trim());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        return blank(value) ? null : value.trim();
    }
}
