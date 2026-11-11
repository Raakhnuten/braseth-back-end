package com.braseth.ecommerce.config;

import org.springframework.boot.web.codec.CodecCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.codec.multipart.DefaultPartHttpMessageReader;
import org.springframework.http.codec.multipart.MultipartHttpMessageReader;

/**
 * Caps multipart uploads before they reach any controller.
 *
 * Out of the box a part may be any size: the server writes it to a temporary
 * file in full before a controller can check it, so one oversized upload
 * could fill the disk. This applies to the whole application; the limit sits
 * just above the largest artwork file BRASETH accepts, and the artwork
 * endpoint enforces the exact figure itself.
 */
@Configuration
public class UploadLimitsConfig {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    CodecCustomizer uploadLimits(BrasethProperties properties) {
        long maxFileBytes = properties.designs().maxFileSizeBytes();
        return configurer -> {
            DefaultPartHttpMessageReader parts = new DefaultPartHttpMessageReader();
            parts.setMaxDiskUsagePerPart(maxFileBytes + 64 * 1024);
            parts.setMaxInMemorySize(256 * 1024);
            parts.setMaxParts(10);
            configurer.defaultCodecs().multipartReader(new MultipartHttpMessageReader(parts));
        };
    }
}
