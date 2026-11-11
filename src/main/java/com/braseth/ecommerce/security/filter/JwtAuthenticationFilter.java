package com.braseth.ecommerce.security.filter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.braseth.ecommerce.modules.user.entity.User;
import com.braseth.ecommerce.security.jwt.CustomUserDetails;
import com.braseth.ecommerce.security.jwt.JwtService;

import reactor.core.publisher.Mono;

/**
 * Turns a bearer access token into the signed-in principal for /api/v1.
 *
 * The token is verified with {@link JwtService}, then the account and its
 * roles are re-read on each request, so a suspended or deleted user — or a
 * role change — takes effect at once rather than when the token expires.
 * The principal is a {@link CustomUserDetails}.
 *
 * Deliberately not a Spring bean: a bean WebFilter would also be added to the
 * global filter chain for every path.
 */
public class JwtAuthenticationFilter implements WebFilter {

    private final JwtService jwtService;
    private final DatabaseClient db;

    public JwtAuthenticationFilter(JwtService jwtService, DatabaseClient db) {
        this.jwtService = jwtService;
        this.db = db;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return chain.filter(exchange);
        }
        String token = header.substring(7).trim();
        UUID userId = jwtService.validateAccessToken(token) ? jwtService.extractUserId(token) : null;
        if (userId == null) {
            return chain.filter(exchange);
        }
        // Decide the principal first, then run the rest of the chain exactly once.
        // (chain.filter completes empty, so a switchIfEmpty after it would run
        // the request a second time.)
        return principal(userId)
                .map(user -> Optional.of(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())))
                .defaultIfEmpty(Optional.empty())
                .flatMap(auth -> auth.isPresent()
                        ? chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth.get()))
                        : chain.filter(exchange));
    }

    /** Empty when the account is gone, suspended or unverified. */
    private Mono<CustomUserDetails> principal(UUID userId) {
        Mono<User> user = db.sql("""
                SELECT id, name, email, phone, password_hash, status, email_verified_at
                FROM users WHERE id = :id AND deleted_at IS NULL
                """)
                .bind("id", userId)
                .map(row -> {
                    User u = new User();
                    u.setId(row.get("id", UUID.class));
                    u.setName(row.get("name", String.class));
                    u.setEmail(row.get("email", String.class));
                    u.setPhone(row.get("phone", String.class));
                    u.setPasswordHash(row.get("password_hash", String.class));
                    u.setStatus(row.get("status", String.class));
                    var verified = row.get("email_verified_at", OffsetDateTime.class);
                    u.setEmailVerifiedAt(verified == null ? null : verified.toInstant());
                    return u;
                })
                .one()
                .filter(User::isEnabled);

        Mono<List<GrantedAuthority>> authorities = db.sql("""
                SELECT 'ROLE_' || r.code AS authority FROM roles r
                JOIN users u ON r.code = ANY(u.roles)
                WHERE u.id = :id AND r.is_active = true
                UNION
                SELECT p.code FROM roles r
                JOIN users u ON r.code = ANY(u.roles)
                CROSS JOIN unnest(r.permissions) AS p(code)
                WHERE u.id = :id AND r.is_active = true
                """)
                .bind("id", userId)
                .map(row -> (GrantedAuthority) new SimpleGrantedAuthority(row.get("authority", String.class)))
                .all()
                .collect(ArrayList::new, List::add);

        return user.zipWith(authorities, CustomUserDetails::new);
    }
}
