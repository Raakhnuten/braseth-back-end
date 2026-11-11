package com.braseth.ecommerce.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.braseth.ecommerce.security.jwt.JwtProperties;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String frontendUrl;
    private long verificationTokenExpiration;
    private long resetTokenExpiration;
    private JwtProperties jwtProperties;
    private EmailProperties emailProperties;
    private MinioProperties minioProperties;

    @Data
    public static class EmailProperties {
        private String from;
        private String fromName;
    }

    @Data
    public static class MinioProperties {
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucketName;
    }
}