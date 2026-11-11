package com.braseth.ecommerce.modules.staff.service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.config.BrasethProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.UploadObjectArgs;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Photos staff upload for products, services and the portfolio. They are
 * public, so they go to the team's public product-media bucket under
 * {@code media/} and are linked directly. Only photographs are taken —
 * PNG, JPEG and WebP, checked by their content — never SVG, which can carry
 * script.
 *
 * A photo removed in an editor is deleted from the bucket when the editor
 * saves, once nothing else uses it; uploads that were never saved are swept
 * nightly.
 */
@Slf4j
@Service
public class StaffMediaService {

    private record Kind(String mimeType, String extension) {
    }

    private static final Map<String, Kind> BY_EXTENSION = Map.of(
            ".png", new Kind("image/png", ".png"),
            ".jpg", new Kind("image/jpeg", ".jpg"),
            ".jpeg", new Kind("image/jpeg", ".jpg"),
            ".webp", new Kind("image/webp", ".webp"));

    /** Where uploads from the staff screens go; the nightly sweep looks only here. */
    private static final String UPLOADS = "media/";

    /** An upload nobody saved is kept this long, so an editor still open can save it. */
    private static final Duration ABANDONED_AFTER = Duration.ofDays(1);

    /**
     * Whether a photo address is still used anywhere: a product, a service, a
     * portfolio project (removed ones too, which keep their photos), a
     * category, or the site's client logos.
     */
    private static final String IN_USE = """
            SELECT EXISTS (SELECT 1 FROM products WHERE photos @> jsonb_build_array(jsonb_build_object('url', CAST(:url AS text))))
                OR EXISTS (SELECT 1 FROM categories WHERE image_url = :url)
                OR EXISTS (SELECT 1 FROM services WHERE photos @> jsonb_build_array(jsonb_build_object('url', CAST(:url AS text))))
                OR EXISTS (SELECT 1 FROM portfolio_projects WHERE photos @> jsonb_build_array(jsonb_build_object('url', CAST(:url AS text))))
                OR EXISTS (SELECT 1 FROM site_settings
                           WHERE client_logos @> jsonb_build_array(jsonb_build_object('logo', jsonb_build_object('url', CAST(:url AS text)))))
                AS in_use
            """;

    private final MinioClient minio;
    private final DatabaseClient db;
    private final ObjectMapper json;
    private final String bucket;
    private final String objectBaseUrl;
    private final long maxBytes;

    StaffMediaService(MinioClient minio, DatabaseClient db, ObjectMapper json, BrasethProperties properties) {
        this.minio = minio;
        this.db = db;
        this.json = json;
        this.bucket = properties.media().bucket();
        this.objectBaseUrl = properties.media().objectBaseUrl();
        this.maxBytes = properties.media().maxFileSizeBytes();
    }

    // ---- Removing -----------------------------------------------------------

    /**
     * Deletes the stored file behind each address that nothing uses any more:
     * called after an editor saves without photos it had before. Addresses
     * outside this bucket (the site's own /assets, other hosts) are left
     * alone. Runs after the save has committed, so a save that failed never
     * loses a file; a file that cannot be deleted is logged, not an error for
     * the person saving.
     */
    public Mono<Void> removeUnused(Collection<String> urls) {
        return Flux.fromIterable(new LinkedHashSet<>(urls))
                .filter(url -> keyOf(url) != null)
                .concatMap(url -> db.sql(IN_USE)
                        .bind("url", url)
                        .map(row -> Boolean.TRUE.equals(row.get("in_use", Boolean.class)))
                        .one()
                        .filter(inUse -> !inUse)
                        .flatMap(unused -> remove(keyOf(url))))
                .onErrorResume(error -> {
                    log.warn("Could not check or delete removed photos: {}", error.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    /** Every address in {@code before} that {@code after} no longer has. */
    public static List<String> dropped(Collection<String> before, Collection<String> after) {
        Set<String> kept = new HashSet<>(after);
        return before.stream().filter(url -> !kept.contains(url)).distinct().toList();
    }

    /**
     * Nightly: uploads from the staff screens that were never saved (the photo
     * was removed before saving, or the editor was closed) and are over a day
     * old. Only {@code media/} is swept; the team's own folders are not.
     */
    @Scheduled(cron = "${braseth.v1.media-cleanup-cron:0 45 3 * * *}")
    Mono<Void> sweepAbandoned() {
        Instant cutoff = Instant.now().minus(ABANDONED_AFTER);
        return Mono.fromCallable(() -> {
                    List<String> old = new ArrayList<>();
                    for (Result<Item> result : minio.listObjects(ListObjectsArgs.builder()
                            .bucket(bucket).prefix(UPLOADS).recursive(true).build())) {
                        Item item = result.get();
                        if (!item.isDir() && item.lastModified().toInstant().isBefore(cutoff)) {
                            old.add(urlOf(item.objectName()));
                        }
                    }
                    return old;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(this::removeUnused)
                .doOnSuccess(done -> log.info("Swept abandoned photo uploads"))
                .onErrorResume(error -> {
                    log.error("Could not sweep abandoned photo uploads", error);
                    return Mono.empty();
                });
    }

    private Mono<Void> remove(String key) {
        return Mono.fromCallable(() -> {
                    minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
                    log.info("Deleted unused photo {}", key);
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    log.warn("Could not delete photo {}: {}", key, error.getMessage());
                    return Mono.just(false);
                })
                .then();
    }

    /** The object key when the address points into this bucket, else null. */
    String keyOf(String url) {
        String prefix = objectBaseUrl + "/";
        if (url == null || !url.startsWith(prefix)) {
            return null;
        }
        String key = url.substring(prefix.length());
        // Never act on anything that could step outside the key it names.
        return key.isEmpty() || key.contains("..") || key.contains("?") || key.contains("#") ? null : key;
    }

    private String urlOf(String key) {
        return objectBaseUrl + "/" + key;
    }

    /** Stores the photo and answers with its MediaAsset. Alt text is written in the editor, so it starts empty. */
    public Mono<ObjectNode> upload(FilePart part) {
        String name = part.filename() == null ? "" : Path.of(part.filename()).getFileName().toString().trim();
        int dot = name.lastIndexOf('.');
        Kind kind = dot < 0 ? null : BY_EXTENSION.get(name.substring(dot).toLowerCase());
        if (kind == null) {
            return Mono.error(ApiProblem.field("file", "unsupported_type", "Upload a PNG, JPG or WebP photo."));
        }
        return Mono.usingWhen(
                Mono.fromCallable(() -> Files.createTempFile("media-", kind.extension())).subscribeOn(Schedulers.boundedElastic()),
                temp -> {
                    AtomicLong size = new AtomicLong();
                    Flux<DataBuffer> limited = part.content().<DataBuffer>handle((buffer, sink) -> {
                        if (size.addAndGet(buffer.readableByteCount()) > maxBytes) {
                            DataBufferUtils.release(buffer);
                            sink.error(new ApiProblem(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large",
                                    "This photo is larger than " + (maxBytes / (1024 * 1024)) + " MB."));
                        } else {
                            sink.next(buffer);
                        }
                    });
                    return DataBufferUtils.write(limited, temp)
                            .then(Mono.fromCallable(() -> store(temp, kind, size.get())).subscribeOn(Schedulers.boundedElastic()));
                },
                temp -> Mono.fromCallable(() -> Files.deleteIfExists(temp)).subscribeOn(Schedulers.boundedElastic()));
    }

    private ObjectNode store(Path file, Kind kind, long size) throws Exception {
        if (size == 0) {
            throw ApiProblem.field("file", "empty_file", "This file is empty.");
        }
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(16);
        }
        if (!matches(kind, head)) {
            throw ApiProblem.field("file", "content_mismatch", "This file is not a " + kind.extension().substring(1).toUpperCase()
                    + " photo. Please check it and try again.");
        }
        UUID id = UUID.randomUUID();
        String key = "media/" + id + kind.extension();
        minio.uploadObject(UploadObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .filename(file.toString())
                .contentType(kind.mimeType())
                .build());
        ObjectNode out = json.createObjectNode();
        out.put("id", id.toString());
        out.put("url", urlOf(key));
        out.put("alt", "");
        // Width and height let the site reserve the space. Read from the header only, without
        // decoding the picture; WebP has no reader here, so it goes without.
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = in == null ? Collections.emptyIterator() : ImageIO.getImageReaders(in);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in);
                    out.put("width", reader.getWidth(0));
                    out.put("height", reader.getHeight(0));
                } finally {
                    reader.dispose();
                }
            }
        } catch (Exception e) {
            log.debug("Could not read the size of {}: {}", key, e.getMessage());
        }
        return out;
    }

    /** The file's first bytes against its type's signature. */
    private static boolean matches(Kind kind, byte[] head) {
        return switch (kind.mimeType()) {
            case "image/png" -> head.length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            case "image/jpeg" -> head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            case "image/webp" -> head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            default -> false;
        };
    }
}
