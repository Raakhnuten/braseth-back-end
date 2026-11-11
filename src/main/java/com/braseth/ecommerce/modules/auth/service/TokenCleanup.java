package com.braseth.ecommerce.modules.auth.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/** Deletes expired link tokens and sessions once a night, so the tables stay small. */
@Slf4j
@Component
@RequiredArgsConstructor
class TokenCleanup {

    private final TokenService tokens;
    private final RefreshTokenService refreshTokens;

    @Scheduled(cron = "${braseth.v1.token-cleanup-cron:0 30 3 * * *}")
    Mono<Void> deleteExpired() {
        return Mono.when(tokens.deleteExpired(), refreshTokens.deleteExpired())
                .doOnSuccess(done -> log.info("Deleted expired tokens"))
                .doOnError(error -> log.error("Could not delete expired tokens", error));
    }
}
