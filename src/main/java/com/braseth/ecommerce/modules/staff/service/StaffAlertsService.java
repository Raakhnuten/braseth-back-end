package com.braseth.ecommerce.modules.staff.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.common.util.Paging;
import com.braseth.ecommerce.security.jwt.CustomUserDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * The staff alert bell. {@link StaffRecords#alert} gives everyone whose role
 * grants the alert's permission their own copy, with their own read state —
 * the owner reading an alert does not clear it for Sales.
 */
@Service
@RequiredArgsConstructor
public class StaffAlertsService {

    private static final int MAX_PAGE_SIZE = 50;

    private final DatabaseClient db;
    private final ObjectMapper json;

    public Mono<ObjectNode> list(CustomUserDetails me, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Mono<List<ObjectNode>> items = db.sql("""
                SELECT * FROM notifications
                WHERE user_id = :me AND audience = 'staff'
                ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset
                """)
                .bind("me", me.getUserId())
                .bind("limit", safeSize)
                .bind("offset", (safePage - 1) * safeSize)
                .map(row -> {
                    ObjectNode item = json.createObjectNode();
                    item.put("id", row.get("id", UUID.class).toString());
                    item.put("topic", row.get("topic", String.class));
                    item.put("title", row.get("title", String.class));
                    item.put("body", row.get("body", String.class));
                    item.put("createdAt", row.get("created_at", OffsetDateTime.class).toInstant().toString());
                    item.put("read", row.get("read_at", OffsetDateTime.class) != null);
                    item.put("link", row.get("link", String.class));
                    return item;
                })
                .all()
                .collectList();
        Mono<long[]> counts = db.sql("""
                SELECT count(*) AS total, count(*) FILTER (WHERE read_at IS NULL) AS unread
                FROM notifications WHERE user_id = :me AND audience = 'staff'
                """)
                .bind("me", me.getUserId())
                .map(row -> new long[] { row.get("total", Long.class), row.get("unread", Long.class) })
                .one();
        return Mono.zip(items, counts).map(parts -> {
            ObjectNode out = Paging.page(json, parts.getT1(), safePage, safeSize, parts.getT2()[0]);
            out.put("unreadCount", parts.getT2()[1]);
            return out;
        });
    }

    /** Already read counts as success; an alert that is not theirs does not. */
    public Mono<Void> markRead(CustomUserDetails me, String id) {
        UUID alertId;
        try {
            alertId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return Mono.error(notFound());
        }
        return db.sql("""
                UPDATE notifications SET read_at = coalesce(read_at, now())
                WHERE id = :id AND user_id = :me AND audience = 'staff'
                RETURNING id
                """)
                .bind("me", me.getUserId())
                .bind("id", alertId)
                .map(row -> row.get("id", UUID.class))
                .one()
                .switchIfEmpty(Mono.error(StaffAlertsService::notFound))
                .then();
    }

    public Mono<Void> markAllRead(CustomUserDetails me) {
        return db.sql("""
                UPDATE notifications SET read_at = now()
                WHERE user_id = :me AND audience = 'staff' AND read_at IS NULL
                """)
                .bind("me", me.getUserId())
                .then();
    }

    private static ApiProblem notFound() {
        return new ApiProblem(HttpStatus.NOT_FOUND, "not_found", "We could not find that alert.");
    }
}
