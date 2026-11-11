package com.braseth.ecommerce.modules.design.service;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.braseth.ecommerce.config.BrasethProperties;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.UploadObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Customer artwork in object storage (the team's MinIO client).
 *
 * A bucket of its own, never made public: customers' logos are theirs, so a
 * file is only ever reached through a signed link that expires within
 * minutes. The team's public product-media bucket is not used for this.
 */
@Slf4j
@Component
class ArtworkStorage {

    private final MinioClient minio;
    private final String bucket;
    private final int linkMinutes;
    private volatile boolean bucketReady;

    ArtworkStorage(MinioClient minio, BrasethProperties properties) {
        this.minio = minio;
        this.bucket = properties.designs().bucket();
        this.linkMinutes = properties.designs().linkMinutes();
    }

    Mono<Void> put(Path file, String key, String contentType) {
        return blocking(() -> {
            ensureBucket();
            minio.uploadObject(UploadObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .filename(file.toString())
                    .contentType(contentType)
                    .build());
            return null;
        });
    }

    Mono<Void> remove(String key) {
        return blocking(() -> {
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            return null;
        });
    }

    /**
     * A short-lived link. {@code inline} lets a browser show the image; every
     * other file is sent as a download, so an SVG or PDF can never run in the
     * page that opens it.
     */
    String link(String key, String filename, String contentType, boolean inline) {
        try {
            String disposition = (inline ? "inline" : "attachment") + "; filename=\"" + safeName(filename) + "\"";
            return minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(key)
                    .expiry(linkMinutes, TimeUnit.MINUTES)
                    .extraQueryParams(Map.of(
                            "response-content-disposition", disposition,
                            "response-content-type", inline ? contentType : "application/octet-stream"))
                    .build());
        } catch (Exception e) {
            log.error("Could not sign a link for {}: {}", key, e.getMessage());
            return null;
        }
    }

    private void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        bucketReady = true;
    }

    private static String safeName(String name) {
        return name.replaceAll("[\"\\\\\\r\\n]", "_");
    }

    private interface Call {
        Void run() throws Exception;
    }

    /** The MinIO client blocks; keep it off the event loop. */
    private static Mono<Void> blocking(Call call) {
        return Mono.fromCallable(call::run).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
