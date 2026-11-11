package com.braseth.ecommerce.security.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.braseth.ecommerce.modules.user.entity.Permission;
import com.braseth.ecommerce.modules.user.entity.Role;
import com.braseth.ecommerce.modules.user.entity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

/**
 * Signs and reads the API's access tokens (JWTs carrying the user's id, roles
 * and permissions). Refresh tokens are not JWTs: see {@code RefreshTokenService}.
 */
@Slf4j
@Service
public class JwtService {

    private static final String TYPE_CLAIM = "type";
    private static final String LEGACY_REFRESH_TYPE = "refresh";
    private static final String USER_ID_CLAIM = "userId";

    private final JwtProperties jwtProperties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.signingKey = signingKey(jwtProperties.getSecret());
    }

    /** HMAC-SHA256 needs a key of at least 256 bits (32 bytes). */
    static SecretKey signingKey(String secret) {
        // An unset ${JWT_SECRET} reaches us as the literal placeholder text.
        if (secret == null || secret.isBlank() || secret.startsWith("${")) {
            throw new InvalidJwtSecretException("jwt.secret is not set: the JWT_SECRET environment variable is missing.");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new InvalidJwtSecretException("jwt.secret is too short: it is " + bytes.length
                    + " bytes, but signing tokens needs at least 32.");
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    public String generateAccessToken(User user, List<Role> roles, List<Permission> permissions) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim(USER_ID_CLAIM, user.getId().toString())
                .claim("name", user.getName())
                .claim("email", user.getEmail())
                .claim("phone", user.getPhone())
                .claim("roles", roles.stream().map(Role::getCode).toList())
                .claim("permissions", permissions.stream().map(Permission::getCode).toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(jwtProperties.getAccessTokenExpiration())))
                .issuer(jwtProperties.getIssuer())
                .signWith(signingKey)
                .compact();
    }

    /**
     * True for a signed, unexpired access token. Refresh JWTs issued before
     * sessions moved to the database (auth_tokens) share the signing key and carry
     * {@code type=refresh}; they are refused here until they expire.
     */
    public boolean validateAccessToken(String token) {
        try {
            Claims claims = claims(token);
            if (LEGACY_REFRESH_TYPE.equals(claims.get(TYPE_CLAIM, String.class))) {
                log.warn("Refresh token used as access token");
                return false;
            }
            return claims.getExpiration().after(new Date());
        } catch (Exception e) {
            log.warn("Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    /** The token's user id, or null when it cannot be read. */
    public UUID extractUserId(String token) {
        try {
            String userId = claims(token).get(USER_ID_CLAIM, String.class);
            return userId != null ? UUID.fromString(userId) : null;
        } catch (Exception e) {
            log.warn("Failed to extract user ID from token: {}", e.getMessage());
            return null;
        }
    }

    private Claims claims(String token) {
        return Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
    }
}
