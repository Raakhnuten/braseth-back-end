package com.braseth.ecommerce.modules.submission.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.config.AppProperties;
import com.braseth.ecommerce.infrastructure.telegram.TelegramNotifier;
import com.braseth.ecommerce.modules.staff.service.StaffRecords;
import com.braseth.ecommerce.modules.submission.dto.SubmissionBodies.ContactBody;
import com.braseth.ecommerce.modules.submission.dto.SubmissionBodies.ContactReceipt;
import com.braseth.ecommerce.modules.submission.dto.SubmissionBodies.Line;
import com.braseth.ecommerce.modules.submission.dto.SubmissionBodies.NewsletterReceipt;
import com.braseth.ecommerce.modules.submission.dto.SubmissionBodies.QuoteRequestBody;
import com.braseth.ecommerce.modules.submission.dto.SubmissionBodies.QuoteRequestReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.r2dbc.postgresql.codec.Json;
import reactor.core.publisher.Mono;

/**
 * Saves what the public site sends. Nothing here trusts the browser beyond
 * what it validates: product names are looked up, not taken from the
 * request, and only artwork the sender may use can be attached.
 */
@Service
public class SubmissionsService {

    /** What staff see for a line with no catalogue product. */
    private static final String CUSTOM_REQUEST = "Custom request";

    private final DatabaseClient db;
    private final ObjectMapper json;
    private final TransactionalOperator transaction;
    private final StaffRecords records;
    private final TelegramNotifier telegram;
    private final AppProperties app;

    SubmissionsService(DatabaseClient db, ObjectMapper json, ReactiveTransactionManager transactionManager,
            StaffRecords records, TelegramNotifier telegram, AppProperties app) {
        this.records = records;
        this.telegram = telegram;
        this.app = app;
        this.db = db;
        this.json = json;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    private record PlannedLine(UUID productId, int quantity, String printMethod) {
    }

    // ---- Quote requests -----------------------------------------------------

    public Mono<QuoteRequestReceipt> submitQuote(QuoteRequestBody body, UUID customerId) {
        boolean freeVisual = "free-visual".equals(body.purpose());
        List<PlannedLine> lines = new ArrayList<>();
        try {
            if (body.items() != null && !body.items().isEmpty()) {
                for (int i = 0; i < body.items().size(); i++) {
                    Line item = body.items().get(i);
                    lines.add(new PlannedLine(uuid(item.productId(), "items[" + i + "].productId"), item.quantity(),
                            item.printMethod()));
                }
            } else {
                if (!freeVisual && body.product().quantity() < 1) {
                    throw ApiProblem.field("product.quantity", "quantity_required", "Enter a quantity.");
                }
                if (body.product().productId() == null && blank(body.product().productNote())) {
                    throw ApiProblem.field("product.productNote", "product_required",
                            "Tell us what you would like printed.");
                }
                lines.add(new PlannedLine(
                        body.product().productId() == null ? null : uuid(body.product().productId(), "product.productId"),
                        body.product().quantity(), null));
            }
        } catch (ApiProblem problem) {
            return Mono.error(problem);
        }
        List<UUID> designIds;
        try {
            List<UUID> parsed = new ArrayList<>();
            for (String id : new LinkedHashSet<>(body.design().designFileIds())) {
                parsed.add(uuid(id, "design.designFileIds"));
            }
            designIds = parsed;
        } catch (ApiProblem problem) {
            return Mono.error(problem);
        }

        String requestJson;
        try {
            requestJson = json.writeValueAsString(body);
        } catch (Exception e) {
            return Mono.error(e);
        }

        List<UUID> productIds = lines.stream().map(PlannedLine::productId).filter(id -> id != null).distinct().toList();
        return productNames(productIds)
                .flatMap(names -> {
                    for (int i = 0; i < lines.size(); i++) {
                        UUID id = lines.get(i).productId();
                        if (id != null && !names.containsKey(id)) {
                            String field = body.items() != null && !body.items().isEmpty()
                                    ? "items[" + i + "].productId" : "product.productId";
                            return Mono.error(ApiProblem.field(field, "unknown_product",
                                    "We could not find one of the products. Please refresh and try again."));
                        }
                    }
                    return usableDesigns(designIds, customerId).flatMap(usable -> {
                        if (usable.size() != designIds.size()) {
                            return Mono.<QuoteRequestReceipt>error(ApiProblem.field("design.designFileIds",
                                    "unknown_design", "One of the files could not be attached. Please upload it again."));
                        }
                        return insertQuote(body, customerId, freeVisual, requestJson, lines, names, designIds)
                                .as(transaction::transactional)
                                // After the commit, so staff are never told about a request that was not saved.
                                .doOnSuccess(receipt -> telegram.send(quoteAlert(receipt, body, freeVisual, lines,
                                        names, designIds.size())));
                    });
                });
    }

    private Mono<QuoteRequestReceipt> insertQuote(QuoteRequestBody body, UUID customerId, boolean freeVisual,
            String requestJson, List<PlannedLine> lines, Map<UUID, String> names, List<UUID> designIds) {
        var contact = body.contact();
        // The name at the time of the request, so a later rename or delete does not change it.
        ArrayNode lineList = json.createArrayNode();
        for (PlannedLine line : lines) {
            ObjectNode stored = lineList.addObject();
            if (line.productId() == null) {
                stored.putNull("productId");
            } else {
                stored.put("productId", line.productId().toString());
            }
            stored.put("productName", line.productId() == null ? CUSTOM_REQUEST : names.get(line.productId()));
            stored.put("quantity", line.quantity());
            if (line.printMethod() == null) {
                stored.putNull("printMethod");
            } else {
                stored.put("printMethod", line.printMethod());
            }
        }
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO quote_requests (reference, customer_id, purpose, full_name, company_name, email, phone,
                                            telegram, preferred_contact_method, product_note, request, lines,
                                            design_file_ids)
                VALUES ('QR-' || to_char(now(), 'YYYY') || '-' || lpad(nextval('quote_request_reference_seq')::text, 5, '0'),
                        :customer, :purpose, :name, :company, :email, :phone, :telegram, :preferred, :note, :request,
                        :lines, :designs)
                RETURNING id, reference, created_at, status
                """)
                .bind("purpose", freeVisual ? "free-visual" : "quote")
                .bind("name", contact.fullName().trim())
                .bind("phone", contact.phone().trim())
                .bind("preferred", contact.preferredContactMethod())
                .bind("request", Json.of(requestJson))
                .bind("lines", Json.of(lineList.toString()))
                .bind("designs", designIds.toArray(UUID[]::new));
        spec = bindUuid(spec, "customer", customerId);
        spec = bindText(spec, "company", trimToNull(contact.companyName()));
        spec = bindText(spec, "email", trimToNull(contact.email()));
        spec = bindText(spec, "telegram", trimToNull(contact.telegram()));
        spec = bindText(spec, "note", trimToNull(body.product().productNote()));

        return spec.map(row -> new QuoteRequestReceipt(
                        row.get("id", UUID.class).toString(),
                        row.get("reference", String.class),
                        row.get("created_at", OffsetDateTime.class).toInstant(),
                        row.get("status", String.class)))
                .one()
                .flatMap(receipt -> {
                    UUID quoteId = UUID.fromString(receipt.quoteId());
                    // A signed-in sender takes ownership of guest uploads they attached.
                    Mono<Void> claim = customerId == null || designIds.isEmpty() ? Mono.empty() : db.sql("""
                            UPDATE design_files SET customer_id = :customer
                            WHERE id = ANY(:designs) AND customer_id IS NULL
                            """)
                            .bind("customer", customerId)
                            .bind("designs", designIds.toArray(UUID[]::new))
                            .then();
                    // A signed-in customer sees the request land in their notifications.
                    PlannedLine first = lines.get(0);
                    String what = first.productId() == null ? CUSTOM_REQUEST : names.get(first.productId());
                    Mono<Void> notify = records.notifyCustomer(customerId, "quote-submitted", "/customer/quotes/" + quoteId,
                            "Quote request received", receipt.reference() + " · " + what
                                    + (lines.size() > 1 ? " and " + (lines.size() - 1) + " more" : ""));
                    // Everyone who can see quotes hears about the new request.
                    int pieces = lines.stream().mapToInt(PlannedLine::quantity).sum();
                    String summary = contact.fullName().trim() + " · " + what
                            + (lines.size() > 1 ? " and " + (lines.size() - 1) + " more" : "")
                            + (pieces > 0 ? ", " + pieces + " pieces" : "")
                            + (designIds.isEmpty() ? "" : ", " + designIds.size() + (designIds.size() == 1 ? " artwork file" : " artwork files"));
                    Mono<Void> alert = records.alert("quote-requested", "quotes.view",
                            freeVisual ? "New free visual request" : "New quote request", summary,
                            "/admin/quotes/" + quoteId);
                    return claim.then(notify).then(alert).thenReturn(receipt);
                });
    }

    private String quoteAlert(QuoteRequestReceipt receipt, QuoteRequestBody body, boolean freeVisual,
            List<PlannedLine> lines, Map<UUID, String> names, int artworkFiles) {
        List<QuoteTelegramMessage.Item> items = lines.stream()
                .map(line -> new QuoteTelegramMessage.Item(
                        line.productId() == null ? CUSTOM_REQUEST : names.get(line.productId()),
                        line.quantity(), line.printMethod()))
                .toList();
        return QuoteTelegramMessage.build(receipt.reference(), freeVisual, body.contact(), items,
                body.product().productNote(), artworkFiles,
                app.getFrontendUrl() + "/admin/quotes/" + receipt.quoteId());
    }

    /** Names of visible products, by id. */
    private Mono<Map<UUID, String>> productNames(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Mono.just(Map.of());
        }
        return db.sql("""
                SELECT p.id, p.name FROM products p
                WHERE p.id = ANY(:ids) AND p.is_active AND p.deleted_at IS NULL
                """)
                .bind("ids", ids.toArray(UUID[]::new))
                .map(row -> Map.entry(row.get("id", UUID.class), row.get("name", String.class)))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    /**
     * The designs this sender may attach: live, and either theirs or a guest
     * upload nobody has claimed. A guest can only attach unclaimed uploads.
     */
    private Mono<List<UUID>> usableDesigns(List<UUID> ids, UUID customerId) {
        if (ids.isEmpty()) {
            return Mono.just(List.of());
        }
        GenericExecuteSpec spec = db.sql("""
                SELECT id FROM design_files
                WHERE id = ANY(:ids) AND deleted_at IS NULL
                  AND (customer_id IS NULL OR customer_id = CAST(:customer AS uuid))
                """)
                .bind("ids", ids.toArray(UUID[]::new));
        spec = bindUuid(spec, "customer", customerId);
        return spec.map(row -> row.get("id", UUID.class)).all().collectList();
    }

    // ---- Contact messages ---------------------------------------------------

    public Mono<ContactReceipt> submitContact(ContactBody body) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO contact_messages (reference, name, email, phone, subject, message, customer_id)
                VALUES ('CM-' || to_char(now(), 'YYYY') || '-' || lpad(nextval('contact_message_reference_seq')::text, 5, '0'),
                        :name, :email, :phone, :subject, :message,
                        (SELECT u.id FROM users u WHERE lower(u.email) = lower(:email) AND u.deleted_at IS NULL LIMIT 1))
                RETURNING id, reference
                """)
                .bind("name", body.name().trim())
                .bind("email", body.email().trim())
                .bind("subject", body.subject().trim())
                .bind("message", body.message().trim());
        spec = bindText(spec, "phone", trimToNull(body.phone()));
        return spec.map(row -> new String[] { row.get("id", UUID.class).toString(), row.get("reference", String.class) })
                .one()
                .flatMap(saved -> records.alert("message-received", "messages.handle", "New message",
                                body.name().trim() + " · " + body.subject().trim(), "/admin/messages/" + saved[0])
                        .thenReturn(new ContactReceipt(saved[1])))
                .as(transaction::transactional);
    }

    // ---- Newsletter ---------------------------------------------------------

    /** The same answer whether the address was new, subscribed or had left. */
    public Mono<NewsletterReceipt> subscribe(String email) {
        return db.sql("""
                INSERT INTO contact_messages (kind, email, status) VALUES ('newsletter', :email, 'subscribed')
                ON CONFLICT (lower(email)) WHERE kind = 'newsletter' DO UPDATE
                    SET status = 'subscribed', unsubscribed_at = NULL,
                        received_at = CASE WHEN contact_messages.status = 'subscribed'
                                           THEN contact_messages.received_at ELSE now() END
                """)
                .bind("email", email.trim())
                .then()
                .thenReturn(new NewsletterReceipt(true));
    }

    // ---- Helpers ------------------------------------------------------------

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            throw ApiProblem.field(field, "unknown_reference", "Something in this request is out of date. Please refresh and try again.");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        return blank(value) ? null : value.trim();
    }

    private static GenericExecuteSpec bindText(GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindUuid(GenericExecuteSpec spec, String name, UUID value) {
        return value == null ? spec.bindNull(name, UUID.class) : spec.bind(name, value);
    }
}
