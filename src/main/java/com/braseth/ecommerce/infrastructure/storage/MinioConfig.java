package com.braseth.ecommerce.infrastructure.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class MinioConfig {

    private final MinioProperties minioProperties;

    @Bean
    public MinioClient minioClient() {

        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .credentials(
                        minioProperties.getAccessKey(),
                        minioProperties.getSecretKey()
                );
        // S3-compatible services such as Cloudflare R2 need an explicit region ("auto").
        String region = minioProperties.getRegion();
        if (region != null && !region.isBlank()) {
            builder.region(region);
        }
        return builder.build();
    }
}