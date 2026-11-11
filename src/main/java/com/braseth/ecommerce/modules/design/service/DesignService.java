package com.braseth.ecommerce.modules.design.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.common.util.Paging;
import com.braseth.ecommerce.config.BrasethProperties;
import com.braseth.ecommerce.modules.staff.service.StaffRecords;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Readable;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Artwork uploads: from the quote form (guests too) and the customer's
 * design library.
 */
@Slf4j
@Service
public class DesignService {

    public record UploadPolicy(List<String> acceptedMimeTypes, List<String> acceptedExtensions, long maxFileSizeBytes,
            int maxFilesPerRequest) {
    }

    /** The web app's DesignFile. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DesignView(String id, String name, String mimeType, long sizeBytes, String status,
            Instant uploadedAt, String previewUrl, String downloadUrl, String reviewNote, List<String> usedInQuoteIds,
            List<String> usedInOrderIds) {
    }

    private final DatabaseClient db;
    private final ArtworkStorage storage;
    private final long maxBytes;
    private final int maxFiles;
    private final StaffRecords records;

    DesignService(
            DatabaseClient db,
            ArtworkStorage storage,
            StaffRecords records,
            BrasethProperties properties) {
        this.db = db;
        this.storage = storage;
        this.records = records;
        this.maxBytes = properties.designs().maxFileSizeBytes();
        this.maxFiles = properties.designs().maxFilesPerRequest();
    }

    public UploadPolicy policy() {
        return new UploadPolicy(ArtworkTypes.MIME_TYPES, ArtworkTypes.EXTENSIONS, maxBytes, maxFiles);
    }

    int maxFilesPerRequest() {
        return maxFiles;
    }

    /**
     * Streams the upload to a temporary file, stopping as soon as it passes
     * the size cap, checks its content against its extension, stores it, and
     * records it. {@code customerId} is null for a guest.
     */
    public Mono<DesignView> upload(FilePart part, String note, UUID customerId) {
        String name = part.filename() == null ? "" : Path.of(part.filename()).getFileName().toString().trim();
        String extension = ArtworkTypes.extensionOf(name);
        ArtworkTypes.Kind kind = ArtworkTypes.BY_EXTENSION.get(extension);
        if (name.isEmpty() || kind == null) {
            return Mono.error(ApiProblem.field("file", "unsupported_type",
                    "This file type is not accepted. Send PNG, JPG, PDF, SVG, AI or EPS."));
        }
        if (name.length() > 255) {
            return Mono.error(ApiProblem.field("file", "name_too_long", "Please shorten the file name."));
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (cleanNote != null && cleanNote.length() > 1000) {
            return Mono.error(ApiProblem.field("note", "too_long", "Keep the note under 1,000 characters."));
        }

        return Mono.usingWhen(
                Mono.fromCallable(() -> Files.createTempFile("artwork-", extension)).subscribeOn(Schedulers.boundedElastic()),
                temp -> {
                    AtomicLong size = new AtomicLong();
                    Flux<DataBuffer> limited = part.content().<DataBuffer>handle((buffer, sink) -> {
                        if (size.addAndGet(buffer.readableByteCount()) > maxBytes) {
                            DataBufferUtils.release(buffer);
                            sink.error(new ApiProblem(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large",
                                    "This file is larger than " + (maxBytes / (1024 * 1024)) + " MB."));
                        } else {
                            sink.next(buffer);
                        }
                    });
                    return DataBufferUtils.write(limited, temp)
                            .then(Mono.fromCallable(() -> head(temp)).subscribeOn(Schedulers.boundedElastic()))
                            .flatMap(head -> {
                                if (size.get() == 0) {
                                    return Mono.error(ApiProblem.field("file", "empty_file", "This file is empty."));
                                }
                                if (!ArtworkTypes.contentMatches(extension, head)) {
                                    return Mono.error(ApiProblem.field("file", "content_mismatch",
                                            "This file does not look like a " + extension.substring(1).toUpperCase()
                                                    + " file. Please check it and try again."));
                                }
                                UUID id = UUID.randomUUID();
                                String key = "designs/" + id + extension;
                                return storage.put(temp, key, kind.mimeType())
                                        .then(insert(id, customerId, name, kind.mimeType(), size.get(), key, cleanNote))
                                        .onErrorResume(error -> storage.remove(key).onErrorResume(e -> Mono.empty())
                                                .then(Mono.error(error)));
                            });
                },
                temp -> Mono.fromCallable(() -> Files.deleteIfExists(temp)).subscribeOn(Schedulers.boundedElastic()))
                // A signed-in customer's upload goes straight to Production's review queue.
                // A guest's quote-form uploads are announced with their quote request instead.
                .flatMap(id -> (customerId == null ? Mono.<Void>empty()
                        : db.sql("SELECT name FROM users WHERE id = :id")
                                .bind("id", customerId)
                                .map(row -> row.get("name", String.class))
                                .one()
                                .flatMap(customer -> records.alert("artwork-uploaded", "designs.review",
                                        "New artwork to review", customer + " · " + name, "/admin/designs/" + id)))
                        .then(byId(id)));
    }

    private Mono<UUID> insert(UUID id, UUID customerId, String name, String mimeType, long size, String key,
            String note) {
        var spec = db.sql("""
                INSERT INTO design_files (id, customer_id, original_name, mime_type, size_bytes, storage_key, upload_note)
                VALUES (:id, :customer, :name, :mime, :size, :key, :note)
                """)
                .bind("id", id)
                .bind("name", name)
                .bind("mime", mimeType)
                .bind("size", size)
                .bind("key", key);
        spec = customerId == null ? spec.bindNull("customer", UUID.class) : spec.bind("customer", customerId);
        spec = note == null ? spec.bindNull("note", String.class) : spec.bind("note", note);
        return spec.then().thenReturn(id);
    }

    /** One design with its usage, whoever owns it; callers check ownership. */
    Mono<DesignView> byId(UUID id) {
        return db.sql(DESIGN_SELECT + " WHERE d.id = :id AND d.deleted_at IS NULL")
                .bind("id", id)
                .map(this::view)
                .one();
    }

    static final String DESIGN_SELECT = """
            SELECT d.*,
                   coalesce((SELECT jsonb_agg(r.id ORDER BY r.id)
                             FROM quote_requests r WHERE d.id = ANY(r.design_file_ids)), '[]') AS quote_ids,
                   coalesce((SELECT jsonb_agg(DISTINCT r.converted_order_id)
                             FROM quote_requests r
                             WHERE d.id = ANY(r.design_file_ids) AND r.converted_order_id IS NOT NULL), '[]') AS order_ids
            FROM design_files d
            """;

    /**
     * The artwork attached to a quote, for staff — including files the
     * customer has since removed from their library, which BRASETH keeps
     * for the job.
     */
    public Mono<List<DesignView>> forQuote(UUID quoteId) {
        return db.sql(DESIGN_SELECT + """
                WHERE d.id = ANY(SELECT unnest(design_file_ids) FROM quote_requests WHERE id = :quote)
                ORDER BY d.uploaded_at, d.id
                """)
                .bind("quote", quoteId)
                .map(this::view)
                .all()
                .collectList();
    }

    // ---- The customer's library ---------------------------------------------

    public Mono<ObjectNode> listMine(UUID customerId, int page, int size,
            ObjectMapper json) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 48);
        Mono<List<DesignView>> items = db.sql(DESIGN_SELECT + """
                WHERE d.customer_id = :customer AND d.deleted_at IS NULL
                ORDER BY d.uploaded_at DESC, d.id
                LIMIT :limit OFFSET :offset
                """)
                .bind("customer", customerId)
                .bind("limit", safeSize)
                .bind("offset", (safePage - 1) * safeSize)
                .map(this::view)
                .all()
                .collectList();
        Mono<Long> total = db.sql("SELECT count(*) AS n FROM design_files WHERE customer_id = :customer AND deleted_at IS NULL")
                .bind("customer", customerId)
                .map(row -> row.get("n", Long.class))
                .one();
        return Mono.zip(items, total).map(parts -> {
            List<JsonNode> views = parts.getT1().stream().<JsonNode>map(json::valueToTree).toList();
            return Paging.page(json, views, safePage, safeSize, parts.getT2());
        });
    }

    public Mono<DesignView> mine(UUID customerId, UUID id) {
        return db.sql(DESIGN_SELECT + " WHERE d.id = :id AND d.customer_id = :customer AND d.deleted_at IS NULL")
                .bind("id", id)
                .bind("customer", customerId)
                .map(this::view)
                .one()
                .switchIfEmpty(Mono.error(() -> notFound()));
    }

    /**
     * Takes a file out of the customer's library. If a quote or order uses
     * it, BRASETH keeps it for that job; otherwise the stored file goes too.
     */
    public Mono<Void> removeMine(UUID customerId, UUID id) {
        return db.sql("""
                UPDATE design_files SET deleted_at = now()
                WHERE id = :id AND customer_id = :customer AND deleted_at IS NULL
                RETURNING storage_key,
                          EXISTS (SELECT 1 FROM quote_requests q WHERE design_files.id = ANY(q.design_file_ids)) AS in_use
                """)
                .bind("id", id)
                .bind("customer", customerId)
                .map(row -> Map.entry(row.get("storage_key", String.class), Boolean.TRUE.equals(row.get("in_use", Boolean.class))))
                .one()
                .switchIfEmpty(Mono.error(() -> notFound()))
                .flatMap(removed -> removed.getValue()
                        ? Mono.empty()
                        : storage.remove(removed.getKey())
                                .onErrorResume(error -> {
                                    log.warn("Could not delete stored artwork {}: {}", removed.getKey(), error.getMessage());
                                    return Mono.empty();
                                }));
    }

    private static ApiProblem notFound() {
        return new ApiProblem(HttpStatus.NOT_FOUND, "not_found", "We could not find that file.");
    }

    public DesignView view(Readable row) {
        String key = row.get("storage_key", String.class);
        String name = row.get("original_name", String.class);
        String mime = row.get("mime_type", String.class);
        boolean previewable = mime.equals("image/png") || mime.equals("image/jpeg");
        String quoteIds = row.get("quote_ids", Json.class).asString();
        OffsetDateTime uploaded = row.get("uploaded_at", OffsetDateTime.class);
        return new DesignView(
                row.get("id", UUID.class).toString(),
                name,
                mime,
                row.get("size_bytes", Long.class),
                row.get("status", String.class),
                uploaded == null ? null : uploaded.toInstant(),
                previewable ? storage.link(key, name, mime, true) : null,
                storage.link(key, name, mime, false),
                row.get("review_note", String.class),
                ids(quoteIds),
                ids(row.get("order_ids", Json.class).asString()));
    }

    private static List<String> ids(String jsonArray) {
        String inner = jsonArray.replaceAll("[\\[\\]\\s\"]", "");
        return inner.isEmpty() ? List.of() : List.of(inner.split(","));
    }

    private static byte[] head(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(1024);
        }
    }
}
