package com.braseth.ecommerce.modules.staff.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;

import com.braseth.ecommerce.common.util.Paging;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.r2dbc.spi.Readable;
import reactor.core.publisher.Mono;

/**
 * Reads the activity log (ActivityEntry). Entries are written only by
 * {@link StaffRecords#activity} as changes happen; nothing here writes.
 * Date filters are whole days in Phnom Penh.
 */
@Service
public class StaffActivityService {

    private final DatabaseClient db;
    private final ObjectMapper json;

    StaffActivityService(DatabaseClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public Mono<ObjectNode> list(String area, UUID subjectId, String q, LocalDate from, LocalDate to, int page, int size) {
        int safePage = StaffViews.page(page);
        int safeSize = StaffViews.size(size);
        String where = """
                WHERE a.kind = 'staff'
                  AND (CAST(:area AS text) IS NULL OR a.area = :area)
                  AND (CAST(:subject AS uuid) IS NULL OR a.subject_id = :subject)
                  AND (CAST(:q AS text) IS NULL
                       OR strpos(lower(a.actor_name), lower(:q)) > 0
                       OR strpos(lower(a.subject_label), lower(:q)) > 0
                       OR strpos(lower(a.body), lower(:q)) > 0)
                  AND (CAST(:from AS date) IS NULL OR a.at >= CAST(:from AS date)::timestamp AT TIME ZONE 'Asia/Phnom_Penh')
                  AND (CAST(:to AS date) IS NULL OR a.at < (CAST(:to AS date) + 1)::timestamp AT TIME ZONE 'Asia/Phnom_Penh')
                """;
        GenericExecuteSpec rows = filters(db.sql("SELECT a.* FROM activity_log a " + where
                + " ORDER BY a.at DESC, a.id LIMIT :limit OFFSET :offset"), area, subjectId, q, from, to)
                .bind("limit", safeSize)
                .bind("offset", (safePage - 1) * safeSize);
        GenericExecuteSpec count = filters(db.sql("SELECT count(*) AS n FROM activity_log a " + where),
                area, subjectId, q, from, to);
        return Mono.zip(rows.map(this::entry).all().collectList(), count.map(row -> row.get("n", Long.class)).one())
                .map(parts -> Paging.page(json, parts.getT1(), safePage, safeSize, parts.getT2()));
    }

    private ObjectNode entry(Readable row) {
        ObjectNode out = json.createObjectNode();
        out.put("id", row.get("id", UUID.class).toString());
        out.put("at", row.get("at", OffsetDateTime.class).toInstant().toString());
        UUID actor = row.get("actor_id", UUID.class);
        out.putObject("actor")
                .put("id", actor == null ? "" : actor.toString())
                .put("name", row.get("actor_name", String.class));
        out.put("area", row.get("area", String.class));
        StaffViews.id(out, "subjectId", row.get("subject_id", UUID.class));
        out.put("subjectLabel", row.get("subject_label", String.class));
        out.put("summary", row.get("body", String.class));
        return out;
    }

    private static GenericExecuteSpec filters(GenericExecuteSpec spec, String area, UUID subjectId, String q,
            LocalDate from, LocalDate to) {
        spec = StaffViews.bind(spec, "area", area, String.class);
        spec = StaffViews.bind(spec, "subject", subjectId, UUID.class);
        spec = StaffViews.bind(spec, "q", q, String.class);
        spec = StaffViews.bind(spec, "from", from, LocalDate.class);
        return StaffViews.bind(spec, "to", to, LocalDate.class);
    }
}
