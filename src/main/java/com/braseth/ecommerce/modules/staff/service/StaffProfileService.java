package com.braseth.ecommerce.modules.staff.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.modules.staff.dto.StaffMember;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Who a member of staff is and what they may do, in the terms the web app's
 * staff area uses. A customer account has no staff roles and gets 403.
 */
@Service
@RequiredArgsConstructor
public class StaffProfileService {

    /** Database role code → the staff roles the web app knows. Admin is the only staff role. */
    private static final Map<String, List<String>> STAFF_ROLES = Map.of("ROLE_ADMIN", List.of("owner"));

    private final DatabaseClient db;

    public Mono<StaffMember> profile(UUID userId) {
        return db.sql("""
                SELECT u.name, u.email, r.code AS role_code, p.code AS permission_code
                FROM users u
                JOIN roles r ON r.code = ANY(u.roles) AND r.is_active = true
                LEFT JOIN LATERAL unnest(r.permissions) AS p(code) ON true
                WHERE u.id = :id AND u.deleted_at IS NULL AND u.status = 'active'
                """)
                .bind("id", userId)
                .map(row -> new Row(
                        row.get("name", String.class),
                        row.get("email", String.class),
                        row.get("role_code", String.class),
                        row.get("permission_code", String.class)))
                .all()
                .collectList()
                .flatMap(rows -> toMember(userId, rows));
    }

    private static Mono<StaffMember> toMember(UUID userId, List<Row> rows) {
        Set<String> roles = new TreeSet<>();
        Set<String> permissions = new TreeSet<>();
        for (Row row : rows) {
            roles.addAll(STAFF_ROLES.getOrDefault(row.roleCode(), List.of()));
            if (row.permissionCode() != null && STAFF_ROLES.containsKey(row.roleCode())) {
                permissions.add(row.permissionCode());
            }
        }
        if (roles.isEmpty()) {
            return Mono.error(ApiProblem.forbidden("not_staff", "This account does not have staff access."));
        }
        Row first = rows.get(0);
        return Mono.just(new StaffMember(userId.toString(), first.name(), first.email(),
                List.copyOf(roles), List.copyOf(permissions)));
    }

    private record Row(String name, String email, String roleCode, String permissionCode) {
    }
}
