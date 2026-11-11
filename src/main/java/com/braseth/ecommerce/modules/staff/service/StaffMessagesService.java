package com.braseth.ecommerce.modules.staff.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.braseth.ecommerce.common.util.Csv;
import com.braseth.ecommerce.common.util.Paging;
import com.braseth.ecommerce.modules.staff.service.StaffGate.Staff;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.r2dbc.spi.Readable;
import reactor.core.publisher.Mono;

/**
 * The contact-form inbox and the newsletter list, both in contact_messages
 * (kind 'message' and 'newsletter'). Staff reply from their own email
 * program; here they only record that a message was answered.
 */
@Service
public class StaffMessagesService {

    private static final int PREVIEW_LENGTH = 140;

    private static final String SELECT = """
            SELECT m.*, r.name AS replied_by_name
            FROM contact_messages m LEFT JOIN users r ON r.id = m.replied_by
            """;

    private final DatabaseClient db;
    private final ObjectMapper json;
    private final StaffRecords records;
    private final TransactionalOperator transaction;

    StaffMessagesService(DatabaseClient db, ObjectMapper json, StaffRecords records,
            ReactiveTransactionManager transactionManager) {
        this.db = db;
        this.json = json;
        this.records = records;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    // ---- Messages -----------------------------------------------------------

    /** {@code filter} "open" is new and read together. */
    public Mono<ObjectNode> list(String filter, String q, int page, int size) {
        int safePage = StaffViews.page(page);
        int safeSize = StaffViews.size(size);
        String where = """
                WHERE m.kind = 'message'
                  AND (CAST(:filter AS text) IS NULL
                       OR (:filter = 'open' AND m.status IN ('new', 'read'))
                       OR m.status = :filter)
                  AND (CAST(:q AS text) IS NULL
                       OR strpos(lower(m.name), lower(:q)) > 0
                       OR strpos(lower(m.email), lower(:q)) > 0
                       OR strpos(lower(m.subject), lower(:q)) > 0
                       OR strpos(lower(m.reference), lower(:q)) > 0)
                """;
        GenericExecuteSpec rows = messageFilters(db.sql(SELECT + where
                + " ORDER BY m.received_at DESC LIMIT :limit OFFSET :offset"), filter, q)
                .bind("limit", safeSize)
                .bind("offset", (safePage - 1) * safeSize);
        GenericExecuteSpec count = messageFilters(db.sql("SELECT count(*) AS n FROM contact_messages m " + where), filter, q);
        return Mono.zip(rows.map(this::summary).all().collectList(), count.map(row -> row.get("n", Long.class)).one())
                .map(parts -> Paging.page(json, parts.getT1(), safePage, safeSize, parts.getT2()));
    }

    public Mono<ObjectNode> detail(UUID id) {
        return db.sql(SELECT + " WHERE m.id = :id AND m.kind = 'message'")
                .bind("id", id)
                .map(this::detailView)
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("message")));
    }

    /**
     * Moves a message between new, read, replied and archived. Marking it
     * replied records who and when; moving it back keeps that history.
     */
    public Mono<ObjectNode> setStatus(Staff actor, UUID id, String status) {
        return db.sql("""
                WITH previous AS (SELECT id, status FROM contact_messages WHERE id = :id AND kind = 'message' FOR UPDATE)
                UPDATE contact_messages m SET status = :status,
                       replied_at = CASE WHEN :status = 'replied' THEN now() ELSE m.replied_at END,
                       replied_by = CASE WHEN :status = 'replied' THEN :actor ELSE m.replied_by END,
                       updated_at = now(), updated_by = :actor
                FROM previous WHERE m.id = previous.id
                RETURNING m.reference, previous.status AS previous
                """)
                .bind("status", status)
                .bind("actor", actor.id())
                .bind("id", id)
                .map(row -> new String[] { row.get("reference", String.class), row.get("previous", String.class) })
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("message")))
                // Opening a message marks it read; that is not worth a line in the log.
                .flatMap(change -> status.equals(change[1]) || "read".equals(status) && "new".equals(change[1])
                        ? Mono.<Void>empty()
                        : records.activity(actor, "message", id, change[0], summaryFor(status)))
                .as(transaction::transactional)
                .then(detail(id));
    }

    private static String summaryFor(String status) {
        return switch (status) {
            case "replied" -> "Marked as replied";
            case "archived" -> "Archived";
            case "new" -> "Marked as unread";
            default -> "Moved back to the inbox";
        };
    }

    private ObjectNode summary(Readable row) {
        ObjectNode out = json.createObjectNode();
        out.put("id", row.get("id", UUID.class).toString());
        out.put("reference", row.get("reference", String.class));
        out.put("receivedAt", row.get("received_at", OffsetDateTime.class).toInstant().toString());
        out.put("name", row.get("name", String.class));
        out.put("email", row.get("email", String.class));
        out.put("subject", row.get("subject", String.class));
        out.put("preview", preview(row.get("message", String.class)));
        out.put("status", row.get("status", String.class));
        return out;
    }

    private ObjectNode detailView(Readable row) {
        ObjectNode out = summary(row);
        StaffViews.text(out, "phone", row.get("phone", String.class));
        out.put("message", row.get("message", String.class));
        StaffViews.id(out, "customerId", row.get("customer_id", UUID.class));
        StaffViews.instant(out, "repliedAt", row.get("replied_at", OffsetDateTime.class));
        StaffViews.text(out, "repliedBy", row.get("replied_by_name", String.class));
        return out;
    }

    /** The first line, cut at a word near {@value #PREVIEW_LENGTH} characters. */
    static String preview(String message) {
        String flat = message.strip().replaceAll("\\s+", " ");
        if (flat.length() <= PREVIEW_LENGTH) {
            return flat;
        }
        int cut = flat.lastIndexOf(' ', PREVIEW_LENGTH);
        return flat.substring(0, cut > PREVIEW_LENGTH / 2 ? cut : PREVIEW_LENGTH) + "…";
    }

    private static GenericExecuteSpec messageFilters(GenericExecuteSpec spec, String filter, String q) {
        spec = StaffViews.bind(spec, "filter", filter, String.class);
        return StaffViews.bind(spec, "q", q, String.class);
    }

    // ---- Newsletter ---------------------------------------------------------

    private static final String SUBSCRIBERS_WHERE = """
            WHERE s.kind = 'newsletter'
              AND (CAST(:status AS text) IS NULL OR s.status = :status)
              AND (CAST(:q AS text) IS NULL OR strpos(lower(s.email), lower(:q)) > 0)
            """;

    public Mono<ObjectNode> subscribers(String status, String q, int page, int size) {
        int safePage = StaffViews.page(page);
        int safeSize = StaffViews.size(size);
        GenericExecuteSpec rows = subscriberFilters(db.sql("SELECT s.* FROM contact_messages s " + SUBSCRIBERS_WHERE
                + " ORDER BY s.received_at DESC, s.id LIMIT :limit OFFSET :offset"), status, q)
                .bind("limit", safeSize)
                .bind("offset", (safePage - 1) * safeSize);
        GenericExecuteSpec count = subscriberFilters(
                db.sql("SELECT count(*) AS n FROM contact_messages s " + SUBSCRIBERS_WHERE), status, q);
        return Mono.zip(rows.map(this::subscriber).all().collectList(), count.map(row -> row.get("n", Long.class)).one())
                .map(parts -> Paging.page(json, parts.getT1(), safePage, safeSize, parts.getT2()));
    }

    /** The whole filtered list as CSV, newest first. */
    public Mono<String> exportSubscribers(String status, String q) {
        return subscriberFilters(db.sql("SELECT s.* FROM contact_messages s " + SUBSCRIBERS_WHERE
                + " ORDER BY s.received_at DESC, s.id"), status, q)
                .map(row -> List.<Object>of(
                        row.get("email", String.class),
                        row.get("status", String.class),
                        row.get("received_at", OffsetDateTime.class).toInstant().toString(),
                        instantText(row.get("unsubscribed_at", OffsetDateTime.class))))
                .all()
                .collectList()
                .map(lines -> {
                    List<List<Object>> rows = new ArrayList<>();
                    rows.add(List.of("email", "status", "subscribed_at", "unsubscribed_at"));
                    rows.addAll(lines);
                    return Csv.of(rows);
                });
    }

    private static GenericExecuteSpec subscriberFilters(GenericExecuteSpec spec, String status, String q) {
        spec = StaffViews.bind(spec, "status", status, String.class);
        return StaffViews.bind(spec, "q", q, String.class);
    }

    private ObjectNode subscriber(Readable row) {
        ObjectNode out = json.createObjectNode();
        out.put("id", row.get("id", UUID.class).toString());
        out.put("email", row.get("email", String.class));
        out.put("status", row.get("status", String.class));
        out.put("subscribedAt", row.get("received_at", OffsetDateTime.class).toInstant().toString());
        StaffViews.instant(out, "unsubscribedAt", row.get("unsubscribed_at", OffsetDateTime.class));
        return out;
    }

    private static String instantText(OffsetDateTime value) {
        return value == null ? "" : value.toInstant().toString();
    }
}
