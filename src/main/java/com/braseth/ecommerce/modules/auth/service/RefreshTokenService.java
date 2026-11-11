package com.braseth.ecommerce.modules.auth.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import com.braseth.ecommerce.common.util.SecureTokens;
import com.braseth.ecommerce.security.jwt.JwtProperties;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Sessions: an opaque refresh token per sign-in, stored as a hash in
 * auth_tokens (purpose 'refresh').
 * Revoking one is permanent and shared by every instance of the app.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final DatabaseClient db;
    private final JwtProperties jwtProperties;

    public Mono<String> issue(UUID userId) {
        String token = SecureTokens.generate();
        return db.sql("INSERT INTO auth_tokens (user_id, purpose, token_hash, expires_at) VALUES (:user, 'refresh', :hash, :expires)")
                .bind("user", userId)
                .bind("hash", SecureTokens.hash(token))
                .bind("expires", OffsetDateTime.now(ZoneOffset.UTC)
                        .plus(Duration.ofMillis(jwtProperties.getRefreshTokenExpiration())))
                .then()
                .thenReturn(token);
    }

    /** The session's user, or empty when the token is unknown, revoked or expired. */
    public Mono<UUID> userOf(String token) {
        return db.sql("""
                SELECT user_id FROM auth_tokens
                WHERE token_hash = :hash AND purpose = 'refresh' AND used_at IS NULL AND expires_at > now()
                """)
                .bind("hash", SecureTokens.hash(token))
                .map(row -> row.get("user_id", UUID.class))
                .one();
    }

    /** Ends one session (logout). Unknown or already revoked tokens are ignored. */
    public Mono<Void> revoke(String token) {
        return db.sql("UPDATE auth_tokens SET used_at = now() WHERE token_hash = :hash AND purpose = 'refresh' AND used_at IS NULL")
                .bind("hash", SecureTokens.hash(token))
                .then();
    }

    /** Ends every session of a user, e.g. after their password changed. */
    public Mono<Void> revokeAll(UUID userId) {
        return db.sql("UPDATE auth_tokens SET used_at = now() WHERE user_id = :user AND purpose = 'refresh' AND used_at IS NULL")
                .bind("user", userId)
                .then();
    }

    /** Removes sessions that can no longer be used. */
    public Mono<Void> deleteExpired() {
        return db.sql("DELETE FROM auth_tokens WHERE purpose = 'refresh' AND expires_at < now()").then();
    }
}
