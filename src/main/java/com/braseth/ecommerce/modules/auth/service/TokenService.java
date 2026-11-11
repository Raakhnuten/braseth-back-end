package com.braseth.ecommerce.modules.auth.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import com.braseth.ecommerce.common.util.SecureTokens;
import com.braseth.ecommerce.config.AppProperties;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * The one-time tokens behind e-mail links: verify an address, reset a password.
 *
 * Only a hash of each token is stored (auth_tokens). Issuing a new link
 * cancels the user's earlier ones of the same kind, and a link is used up in
 * the same statement that checks it, so two requests racing with one link
 * cannot both succeed.
 */
@Service
@RequiredArgsConstructor
public class TokenService {

    private final DatabaseClient db;
    private final AppProperties appProperties;

    /** auth_tokens.purpose for each kind of link. */
    private enum Kind {
        EMAIL_VERIFICATION("email-verification"),
        PASSWORD_RESET("password-reset");

        private final String purpose;

        Kind(String purpose) {
            this.purpose = purpose;
        }
    }

    public Mono<String> issueEmailVerification(UUID userId) {
        return issue(Kind.EMAIL_VERIFICATION, userId, Duration.ofMillis(appProperties.getVerificationTokenExpiration()));
    }

    public Mono<String> issuePasswordReset(UUID userId) {
        return issue(Kind.PASSWORD_RESET, userId, Duration.ofMillis(appProperties.getResetTokenExpiration()));
    }

    /** The user the link was for, or empty when it is unknown, used or expired. */
    public Mono<UUID> consumeEmailVerification(String token) {
        return consume(Kind.EMAIL_VERIFICATION, token);
    }

    /** The user the link was for, or empty when it is unknown, used or expired. */
    public Mono<UUID> consumePasswordReset(String token) {
        return consume(Kind.PASSWORD_RESET, token);
    }

    /** Removes links that can no longer be used. */
    public Mono<Void> deleteExpired() {
        return db.sql("DELETE FROM auth_tokens WHERE purpose IN ('email-verification', 'password-reset') AND expires_at < now()")
                .then();
    }

    private Mono<String> issue(Kind kind, UUID userId, Duration validFor) {
        String token = SecureTokens.generate();
        // One statement, so the earlier links are cancelled only if the new one is stored.
        return db.sql("""
                WITH cancelled AS (
                    UPDATE auth_tokens SET used_at = now()
                    WHERE user_id = :user AND purpose = :purpose AND used_at IS NULL
                )
                INSERT INTO auth_tokens (user_id, purpose, token_hash, expires_at)
                VALUES (:user, :purpose, :hash, :expires)
                """)
                .bind("user", userId)
                .bind("purpose", kind.purpose)
                .bind("hash", SecureTokens.hash(token))
                .bind("expires", OffsetDateTime.now(ZoneOffset.UTC).plus(validFor))
                .then()
                .thenReturn(token);
    }

    private Mono<UUID> consume(Kind kind, String token) {
        return db.sql("""
                UPDATE auth_tokens SET used_at = now()
                WHERE token_hash = :hash AND purpose = :purpose AND used_at IS NULL AND expires_at > now()
                RETURNING user_id
                """)
                .bind("hash", SecureTokens.hash(token))
                .bind("purpose", kind.purpose)
                .map(row -> row.get("user_id", UUID.class))
                .one();
    }
}
