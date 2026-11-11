package com.braseth.ecommerce.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    private String endpoint;

    private String accessKey;

    private String secretKey;

    private String bucketName;

    /** Optional signing region; empty for MinIO, "auto" for Cloudflare R2. */
    private String region;
}