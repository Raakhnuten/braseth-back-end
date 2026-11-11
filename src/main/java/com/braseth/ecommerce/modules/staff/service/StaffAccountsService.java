package com.braseth.ecommerce.modules.staff.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.common.util.SecureTokens;
import com.braseth.ecommerce.modules.auth.service.AuthService;
import com.braseth.ecommerce.modules.auth.service.RefreshTokenService;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.StaffInvite;
import com.braseth.ecommerce.modules.staff.service.StaffGate.Staff;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.r2dbc.spi.Readable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The staff list, for the owner (AdminStaffAccount). An invitation emails a
 * link to set a password; nobody here sees or chooses one. The business is
 * never left without an active owner.
 */
@Service
public class StaffAccountsService {

    /** The web app's staff role and the database role it stands for. Admin is the only staff role. */
    private static final Map<String, String> ROLE_CODE = Map.of("owner", "ROLE_ADMIN");

    /** Database role → web roles. */
    private static final Map<String, List<String>> WEB_ROLES = Map.of("ROLE_ADMIN", List.of("owner"));

    private static final String STAFF_CODES = "ARRAY['ROLE_ADMIN']";

    private static final String SELECT = """
            SELECT u.id, u.name, u.email, u.status, u.created_at, u.last_login_at, u.roles AS role_codes
            FROM users u
            WHERE u.roles && %s AND u.deleted_at IS NULL
            """.formatted(STAFF_CODES);

    private final DatabaseClient db;
    private final ObjectMapper json;
    private final PasswordEncoder passwordEncoder;
    private final AuthService auth;
    private final RefreshTokenService refreshTokens;
    private final StaffRecords records;
    private final TransactionalOperator transaction;

    StaffAccountsService(DatabaseClient db, ObjectMapper json, PasswordEncoder passwordEncoder, AuthService auth,
            RefreshTokenService refreshTokens, StaffRecords records, ReactiveTransactionManager transactionManager) {
        this.db = db;
        this.json = json;
        this.passwordEncoder = passwordEncoder;
        this.auth = auth;
        this.refreshTokens = refreshTokens;
        this.records = records;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    /** Everyone with a staff role: active first, then invited, then disabled; by name within each. */
    public Mono<ArrayNode> list() {
        return db.sql(SELECT + """
                ORDER BY CASE u.status WHEN 'active' THEN 0 WHEN 'invited' THEN 1 ELSE 2 END, lower(u.name)
                """)
                .map(this::view)
                .all()
                .collect(json::createArrayNode, ArrayNode::add);
    }

    public Mono<ObjectNode> invite(Staff actor, StaffInvite invite) {
        String email = invite.email().trim();
        List<String> roles = distinct(invite.roles());
        return db.sql("SELECT 1 AS taken FROM users WHERE lower(email) = lower(:email)")
                .bind("email", email)
                .map(row -> true)
                .one()
                .flatMap(taken -> Mono.<UUID>error(ApiProblem.conflict("email", "email_taken",
                        "This address already has an account. Ask the owner to change its roles instead.")))
                // The password is never used: it is replaced when they accept the invitation.
                .switchIfEmpty(Mono.fromCallable(() -> passwordEncoder.encode(SecureTokens.generate()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(hash -> db.sql("""
                                INSERT INTO users (name, email, password_hash, status) VALUES (:name, :email, :hash, 'invited')
                                RETURNING id
                                """)
                                .bind("name", invite.fullName().trim())
                                .bind("email", email)
                                .bind("hash", hash)
                                .map(row -> row.get("id", UUID.class))
                                .one()))
                .flatMap(id -> setStaffRoles(id, roles)
                        .then(records.activity(actor, "staff", id, invite.fullName().trim(),
                                "Invited as " + String.join(", ", roles)))
                        .thenReturn(id))
                .as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> ApiProblem.conflict("email", "email_taken",
                        "This address already has an account."))
                // After the commit, so the link is never sent for an account that was rolled back.
                .flatMap(id -> auth.sendStaffInvitation(id).then(byId(id)));
    }

    public Mono<ObjectNode> setRoles(Staff actor, UUID id, List<String> requested) {
        List<String> roles = distinct(requested);
        return byId(id)
                .flatMap(current -> {
                    boolean wasOwner = hasOwner(current);
                    Mono<Void> guard = wasOwner && !roles.contains("owner") ? requireAnotherOwner(id) : Mono.empty();
                    return guard
                            .then(setStaffRoles(id, roles))
                            .then(records.activity(actor, "staff", id, current.get("fullName").asText(),
                                    "Roles set to " + String.join(", ", roles)));
                })
                .as(transaction::transactional)
                .then(Mono.defer(() -> byId(id)));
    }

    /** Disabling signs them out everywhere at once. An invitation not yet accepted stays invited. */
    public Mono<ObjectNode> setStatus(Staff actor, UUID id, String status) {
        boolean disable = "disabled".equals(status);
        if (disable && id.equals(actor.id())) {
            return Mono.error(new ApiProblem(HttpStatus.CONFLICT, "cannot_disable_self",
                    "You cannot disable your own account."));
        }
        return byId(id)
                .flatMap(current -> {
                    String now = current.get("status").asText();
                    if (!disable && "invited".equals(now)) {
                        return Mono.error(new ApiProblem(HttpStatus.CONFLICT, "still_invited",
                                "They have not accepted the invitation yet."));
                    }
                    if (now.equals(status)) {
                        return Mono.empty();
                    }
                    Mono<Void> guard = disable && hasOwner(current) ? requireAnotherOwner(id) : Mono.empty();
                    return guard
                            .then(db.sql("UPDATE users SET status = :status WHERE id = :id")
                                    .bind("status", status)
                                    .bind("id", id)
                                    .then())
                            .then(disable ? refreshTokens.revokeAll(id) : Mono.empty())
                            .then(records.activity(actor, "staff", id, current.get("fullName").asText(),
                                    disable ? "Disabled the account" : "Re-enabled the account"));
                })
                .as(transaction::transactional)
                .then(Mono.defer(() -> byId(id)));
    }

    // ---- Helpers ------------------------------------------------------------

    private Mono<ObjectNode> byId(UUID id) {
        return db.sql(SELECT + " AND u.id = :id")
                .bind("id", id)
                .map(this::view)
                .one()
                .switchIfEmpty(Mono.error(() -> StaffViews.notFound("member of staff")));
    }

    /** Replaces the account's staff roles with these web roles; any other role (customer) is kept. */
    private Mono<Void> setStaffRoles(UUID id, List<String> roles) {
        String[] codes = roles.stream().map(ROLE_CODE::get).toArray(String[]::new);
        return db.sql("""
                UPDATE users SET roles = ARRAY(
                    SELECT r FROM unnest(roles) AS r WHERE NOT (r = ANY(%s))
                    UNION
                    SELECT unnest(CAST(:codes AS text[])))
                WHERE id = :id
                """.formatted(STAFF_CODES))
                .bind("id", id)
                .bind("codes", codes)
                .then();
    }

    /** Refuses a change that would leave no other active owner. */
    private Mono<Void> requireAnotherOwner(UUID leaving) {
        return db.sql("""
                SELECT count(*) AS n FROM users u
                WHERE 'ROLE_ADMIN' = ANY(u.roles) AND u.status = 'active' AND u.deleted_at IS NULL AND u.id <> :id
                """)
                .bind("id", leaving)
                .map(row -> row.get("n", Long.class))
                .one()
                .flatMap(others -> others > 0 ? Mono.<Void>empty()
                        : Mono.error(new ApiProblem(HttpStatus.CONFLICT, "last_owner",
                                "BRASETH needs at least one active owner. Make someone else an owner first.")));
    }

    private static boolean hasOwner(ObjectNode account) {
        for (var role : account.get("roles")) {
            if ("owner".equals(role.asText())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> distinct(List<String> roles) {
        return new ArrayList<>(new LinkedHashSet<>(roles));
    }

    /** AdminStaffAccount. */
    private ObjectNode view(Readable row) {
        ObjectNode out = json.createObjectNode();
        out.put("id", row.get("id", UUID.class).toString());
        out.put("fullName", row.get("name", String.class));
        out.put("email", row.get("email", String.class));
        Set<String> roles = new TreeSet<>();
        for (String code : row.get("role_codes", String[].class)) {
            roles.addAll(WEB_ROLES.getOrDefault(code, List.of()));
        }
        ArrayNode roleList = out.putArray("roles");
        roles.forEach(roleList::add);
        String status = row.get("status", String.class);
        out.put("status", "active".equals(status) || "invited".equals(status) ? status : "disabled");
        out.put("invitedAt", row.get("created_at", OffsetDateTime.class).toInstant().toString());
        StaffViews.instant(out, "lastSignInAt", row.get("last_login_at", OffsetDateTime.class));
        return out;
    }
}
