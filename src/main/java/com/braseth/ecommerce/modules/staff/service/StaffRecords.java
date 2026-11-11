package com.braseth.ecommerce.modules.staff.service;

import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;

import com.braseth.ecommerce.modules.staff.service.StaffGate.Staff;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * What the backend writes as things happen: the activity log (who changed
 * what), alerts for staff, and notifications for a customer.
 */
@Component
@RequiredArgsConstructor
public class StaffRecords {

    private final DatabaseClient db;

    /**
     * One line in the activity log. {@code summary} is written for staff,
     * e.g. "Priced at $545.00" or "Moved to In production".
     */
    public Mono<Void> activity(Staff actor, String area, UUID subjectId, String subjectLabel, String summary) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO activity_log (kind, actor_id, actor_name, area, subject_id, subject_label, body)
                VALUES ('staff', :actor, :name, :area, :subject, :label, :summary)
                """)
                .bind("actor", actor.id())
                .bind("name", actor.name())
                .bind("area", area)
                .bind("label", clip(subjectLabel, 200))
                .bind("summary", clip(summary, 500));
        spec = subjectId == null ? spec.bindNull("subject", UUID.class) : spec.bind("subject", subjectId);
        return spec.then();
    }

    /**
     * An alert for everyone whose role grants {@code audience} — Sales hears
     * about quote requests, Production about artwork. Each of them gets their
     * own copy, so one person reading it does not clear it for the others.
     */
    public Mono<Void> alert(String topic, String audience, String title, String body, String link) {
        return db.sql("""
                INSERT INTO notifications (user_id, audience, topic, title, body, link)
                SELECT DISTINCT u.id, 'staff', :topic, :title, :body, :link
                FROM users u JOIN roles r ON r.code = ANY(u.roles) AND r.is_active
                WHERE :audience = ANY(r.permissions) AND u.status IN ('active', 'invited') AND u.deleted_at IS NULL
                """)
                .bind("topic", topic)
                .bind("audience", audience)
                .bind("title", clip(title, 200))
                .bind("body", clip(body, 500))
                .bind("link", link)
                .then();
    }

    /**
     * An in-app notification for a customer. {@code topic} is the web app's
     * NotificationTopic and {@code link} where it opens in the customer area.
     * {@code customerId} null (a guest) does nothing: guests hear from staff
     * by phone or Telegram.
     */
    public Mono<Void> notifyCustomer(UUID customerId, String topic, String link, String title, String body) {
        if (customerId == null) {
            return Mono.empty();
        }
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO notifications (user_id, audience, topic, title, body, link)
                VALUES (:customer, 'customer', :topic, :title, :body, :link)
                """)
                .bind("customer", customerId)
                .bind("topic", topic)
                .bind("title", clip(title, 255))
                .bind("body", body);
        spec = link == null ? spec.bindNull("link", String.class) : spec.bind("link", link);
        return spec.then();
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
