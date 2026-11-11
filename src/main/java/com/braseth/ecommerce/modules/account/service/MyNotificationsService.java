package com.braseth.ecommerce.modules.account.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.common.util.Paging;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Mono;

/**
 * A customer's in-app notifications (notifications with audience
 * 'customer'). Each row already carries the web app's NotificationTopic and
 * where it opens, as written by {@code StaffRecords#notifyCustomer}.
 */
@Service
public class MyNotificationsService {

    private final DatabaseClient db;
    private final ObjectMapper json;

    MyNotificationsService(DatabaseClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public Mono<ObjectNode> list(UUID userId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        var items = db.sql("""
                SELECT * FROM notifications WHERE user_id = :user AND audience = 'customer'
                ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset
                """)
                .bind("user", userId)
                .bind("limit", safeSize)
                .bind("offset", (safePage - 1) * safeSize)
                .map(row -> {
                    ObjectNode out = json.createObjectNode();
                    out.put("id", row.get("id", UUID.class).toString());
                    out.put("topic", row.get("topic", String.class));
                    out.put("title", row.get("title", String.class));
                    out.put("body", row.get("body", String.class));
                    out.put("createdAt", row.get("created_at", OffsetDateTime.class).toInstant().toString());
                    out.put("read", row.get("read_at", OffsetDateTime.class) != null);
                    String link = row.get("link", String.class);
                    if (link == null) {
                        out.putNull("link");
                    } else {
                        out.put("link", link);
                    }
                    return out;
                })
                .all()
                .collectList();
        var total = db.sql("SELECT count(*) AS n FROM notifications WHERE user_id = :user AND audience = 'customer'")
                .bind("user", userId)
                .map(row -> row.get("n", Long.class))
                .one();
        return Mono.zip(items, total).map(parts -> Paging.page(json, parts.getT1(), safePage, safeSize, parts.getT2()));
    }

    public Mono<Void> markRead(UUID userId, UUID id) {
        return db.sql("""
                UPDATE notifications SET read_at = coalesce(read_at, now())
                WHERE id = :id AND user_id = :user AND audience = 'customer'
                RETURNING id
                """)
                .bind("id", id)
                .bind("user", userId)
                .map(row -> row.get("id", UUID.class))
                .one()
                .switchIfEmpty(Mono.error(() -> new ApiProblem(HttpStatus.NOT_FOUND, "not_found",
                        "We could not find that notification.")))
                .then();
    }

    public Mono<Void> markAllRead(UUID userId) {
        return db.sql("""
                UPDATE notifications SET read_at = now()
                WHERE user_id = :user AND audience = 'customer' AND read_at IS NULL
                """)
                .bind("user", userId)
                .then();
    }
}
