package com.braseth.ecommerce.modules.staff.service;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.security.jwt.CustomUserDetails;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * The door to every /api/v1/admin endpoint: is this a member of staff, and
 * does their role allow this? Roles and permissions come from the signed-in
 * principal, which the v1 token filter re-reads from the database on every
 * request — so a role change or a disabled account takes effect at once.
 */
@Component
@RequiredArgsConstructor
public class StaffGate {

    /**
     * The role that makes an account staff: Admin (see the roles seed, V002). Authorities are the role
     * code with Spring's "ROLE_" in front, and the codes already start with
     * ROLE_ — hence the doubled prefix.
     */
    private static final Set<String> STAFF_ROLES = Set.of(
            "ROLE_ROLE_ADMIN");

    private final DatabaseClient db;

    /** Who is acting, for the activity log. */
    public record Staff(UUID id, String name) {
    }

    /**
     * The acting member of staff, or 403. {@code permission} null means any
     * member of staff may do this.
     */
    public Mono<Staff> require(CustomUserDetails principal, String permission) {
        if (principal == null) {
            return Mono.error(ApiProblem.unauthorized("unauthenticated", "Please sign in to continue."));
        }
        Set<String> granted = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        if (granted.stream().noneMatch(STAFF_ROLES::contains)) {
            return Mono.error(ApiProblem.forbidden("not_staff", "This account does not have staff access."));
        }
        if (permission != null && !granted.contains(permission)) {
            return Mono.error(ApiProblem.forbidden("forbidden", "Your role does not allow this."));
        }
        return db.sql("SELECT name FROM users WHERE id = :id")
                .bind("id", principal.getUserId())
                .map(row -> new Staff(principal.getUserId(), row.get("name", String.class)))
                .one();
    }
}
