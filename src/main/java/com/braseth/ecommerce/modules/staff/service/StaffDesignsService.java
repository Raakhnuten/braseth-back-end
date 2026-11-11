package com.braseth.ecommerce.modules.staff.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.common.util.JsonNodes;
import com.braseth.ecommerce.common.util.Paging;
import com.braseth.ecommerce.modules.design.service.DesignService;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.DesignReview;
import com.braseth.ecommerce.modules.staff.service.StaffGate.Staff;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.r2dbc.spi.Readable;
import reactor.core.publisher.Mono;

/**
 * The artwork review queue (AdminDesign). The decision is what the customer
 * sees in their design library: "Ready to print", or "Needs a new file" with
 * the note staff wrote. A guest's upload is known by the quote it came with.
 *
 * Files a customer removed from their library still show here while a quote
 * uses them: BRASETH keeps those for the job.
 */
@Service
public class StaffDesignsService {

    private static final Set<String> STATUSES = Set.of("uploaded", "under-review", "approved", "rejected");

    private static final String SELECT = """
            SELECT d.*,
                   coalesce((SELECT jsonb_agg(r.id ORDER BY r.id)
                             FROM quote_requests r WHERE d.id = ANY(r.design_file_ids)), '[]') AS quote_ids,
                   coalesce((SELECT jsonb_agg(DISTINCT r.converted_order_id)
                             FROM quote_requests r
                             WHERE d.id = ANY(r.design_file_ids) AND r.converted_order_id IS NOT NULL), '[]') AS order_ids,
                   coalesce((SELECT jsonb_agg(jsonb_build_object('kind', 'quote', 'id', r.id, 'reference', r.reference)
                                              ORDER BY r.created_at)
                             FROM quote_requests r WHERE d.id = ANY(r.design_file_ids)), '[]')
                   || coalesce((SELECT jsonb_agg(jsonb_build_object('kind', 'order', 'id', o.id, 'reference', o.reference)
                                                 ORDER BY o.created_at)
                                FROM quote_requests r JOIN orders o ON o.quote_request_id = r.id
                                WHERE d.id = ANY(r.design_file_ids)), '[]') AS used_in,
                   coalesce(u.name, fq.full_name, '') AS customer_name,
                   coalesce(nullif(u.company_name, ''), fq.company_name) AS customer_company,
                   coalesce(u.phone, fq.phone, '') AS customer_phone,
                   rv.name AS reviewer_name
            FROM design_files d
            LEFT JOIN users u ON u.id = d.customer_id
            LEFT JOIN LATERAL (
                SELECT r.full_name, r.phone, r.company_name
                FROM quote_requests r
                WHERE d.id = ANY(r.design_file_ids) ORDER BY r.created_at LIMIT 1
            ) fq ON true
            LEFT JOIN users rv ON rv.id = d.reviewed_by
            """;

    private static final String VISIBLE = """
            (d.deleted_at IS NULL OR EXISTS (SELECT 1 FROM quote_requests r WHERE d.id = ANY(r.design_file_ids)))
            """;

    private final DatabaseClient db;
    private final ObjectMapper json;
    private final DesignService designs;
    private final StaffRecords records;
    private final TransactionalOperator transaction;

    StaffDesignsService(DatabaseClient db, ObjectMapper json, DesignService designs, StaffRecords records,
            ReactiveTransactionManager transactionManager) {
        this.db = db;
        this.json = json;
        this.designs = designs;
        this.records = records;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    /**
     * The queue (uploaded and under review) oldest first, so the file waiting
     * longest is on top; decided files newest first.
     */
    public Mono<ObjectNode> list(List<String> statuses, String q, int page, int size) {
        int safePage = StaffViews.page(page);
        int safeSize = StaffViews.size(size);
        List<String> wanted = statuses == null ? List.of() : statuses.stream().filter(STATUSES::contains).toList();
        boolean queue = !wanted.isEmpty() && Set.of("uploaded", "under-review").containsAll(wanted);
        String where = " WHERE " + VISIBLE + """
                  AND (cardinality(CAST(:statuses AS text[])) = 0 OR d.status = ANY(CAST(:statuses AS text[])))
                  AND (CAST(:q AS text) IS NULL
                       OR strpos(lower(d.original_name), lower(:q)) > 0
                       OR strpos(lower(coalesce(u.name, fq.full_name, '')), lower(:q)) > 0
                       OR strpos(lower(coalesce(u.company_name, fq.company_name, '')), lower(:q)) > 0
                       OR strpos(regexp_replace(coalesce(u.phone, fq.phone, ''), '[^0-9]', '', 'g'),
                                 regexp_replace(:q, '[^0-9]', '', 'g')) > 0
                          AND regexp_replace(:q, '[^0-9]', '', 'g') <> '')
                """;
        String order = queue ? " ORDER BY d.uploaded_at, d.id" : " ORDER BY d.uploaded_at DESC, d.id";
        GenericExecuteSpec rows = filters(db.sql(SELECT + where + order + " LIMIT :limit OFFSET :offset"), wanted, q)
                .bind("limit", safeSize)
                .bind("offset", (safePage - 1) * safeSize);
        GenericExecuteSpec count = filters(db.sql("SELECT count(*) AS n FROM (" + SELECT + where + ") d"), wanted, q);
        return Mono.zip(rows.map(this::summary).all().collectList(), count.map(row -> row.get("n", Long.class)).one())
                .map(parts -> Paging.page(json, parts.getT1(), safePage, safeSize, parts.getT2()));
    }

    public Mono<ObjectNode> detail(UUID id) {
        return db.sql(SELECT + " WHERE d.id = :id AND " + VISIBLE)
                .bind("id", id)
                .map(row -> {
                    ObjectNode out = summary(row);
                    StaffViews.text(out, "uploadNote", row.get("upload_note", String.class));
                    StaffViews.instant(out, "reviewedAt", row.get("reviewed_at", OffsetDateTime.class));
                    StaffViews.text(out, "reviewedBy", row.get("reviewer_name", String.class));
                    return out;
                })
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("file")));
    }

    /** Approve, or ask for a new file; asking needs a note, the only place the customer learns what to fix. */
    public Mono<ObjectNode> review(Staff actor, UUID id, DesignReview decision) {
        boolean approved = "approved".equals(decision.status());
        String note = decision.reviewNote() == null || decision.reviewNote().isBlank() ? null : decision.reviewNote().trim();
        if (!approved && note == null) {
            return Mono.error(ApiProblem.field("reviewNote", "note_required",
                    "Tell the customer what to change in the file."));
        }
        GenericExecuteSpec spec = db.sql("""
                UPDATE design_files SET status = :status, review_note = :note, reviewed_at = now(), reviewed_by = :actor
                WHERE id = :id
                RETURNING original_name, customer_id
                """)
                .bind("status", decision.status())
                .bind("actor", actor.id())
                .bind("id", id);
        spec = StaffViews.bind(spec, "note", approved ? null : note, String.class);
        return spec.map(row -> new Object[] { row.get("original_name", String.class), row.get("customer_id", UUID.class) })
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("file")))
                .flatMap(file -> {
                    String name = (String) file[0];
                    return records.notifyCustomer((UUID) file[1], "design-status", "/customer/designs",
                                    approved ? "Your artwork is ready to print" : "Your artwork needs a new file",
                                    approved ? name : name + " · " + note)
                            .then(records.activity(actor, "design", id, name,
                                    approved ? "Approved for print" : "Asked for a new file: " + note));
                })
                .as(transaction::transactional)
                .then(Mono.defer(() -> detail(id)));
    }

    /** AdminDesignSummary: the customer's DesignFile plus who sent it and where it is used. */
    private ObjectNode summary(Readable row) {
        ObjectNode out = json.valueToTree(designs.view(row));
        StaffViews.id(out, "customerId", row.get("customer_id", UUID.class));
        out.put("customerName", row.get("customer_name", String.class));
        StaffViews.text(out, "companyName", row.get("customer_company", String.class));
        out.put("phone", row.get("customer_phone", String.class));
        var used = JsonNodes.column(json, row, "used_in");
        out.set("usedIn", used.isArray() ? used : json.createArrayNode());
        return out;
    }

    private static GenericExecuteSpec filters(GenericExecuteSpec spec, List<String> statuses, String q) {
        spec = spec.bind("statuses", statuses.toArray(String[]::new));
        return StaffViews.bind(spec, "q", q, String.class);
    }
}
