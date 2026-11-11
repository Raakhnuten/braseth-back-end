package com.braseth.ecommerce.modules.auth.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import com.braseth.ecommerce.modules.user.entity.Permission;
import com.braseth.ecommerce.modules.user.entity.Role;
import com.braseth.ecommerce.modules.user.entity.User;

import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * The SQL sign-in needs, written directly rather than through Spring Data
 * repositories because:
 *  - e-mail must match case-insensitively ("Ana@x.com" signs in as "ana@x.com");
 *  - saving a {@code User} through a repository writes NULL into NOT NULL
 *    columns ({@code status}, {@code created_at}) because auditing is off.
 * Inserts here let the database fill its own defaults instead.
 */
@Component
@RequiredArgsConstructor
public class AccountQueries {

    private final DatabaseClient db;

    public Mono<User> findUserByEmail(String email) {
        return db.sql("""
                SELECT * FROM users
                WHERE lower(email) = lower(:email) AND deleted_at IS NULL
                LIMIT 1
                """)
                .bind("email", email)
                .map(AccountQueries::toUser)
                .one();
    }

    public Mono<User> findUserById(UUID id) {
        return db.sql("SELECT * FROM users WHERE id = :id AND deleted_at IS NULL")
                .bind("id", id)
                .map(AccountQueries::toUser)
                .one();
    }

    public Mono<Boolean> phoneTaken(String phone) {
        return db.sql("SELECT 1 FROM users WHERE phone = :phone AND deleted_at IS NULL LIMIT 1")
                .bind("phone", phone)
                .map(row -> true)
                .one()
                .defaultIfEmpty(false);
    }

    /** Active roles only. */
    public Mono<List<Role>> rolesOf(UUID userId) {
        return db.sql("""
                SELECT r.id, r.code, r.name FROM roles r
                JOIN users u ON r.code = ANY(u.roles)
                WHERE u.id = :userId AND r.is_active = true
                """)
                .bind("userId", userId)
                .map(row -> {
                    Role role = new Role();
                    role.setId(row.get("id", UUID.class));
                    role.setCode(row.get("code", String.class));
                    role.setName(row.get("name", String.class));
                    role.setActive(true);
                    return role;
                })
                .all()
                .collectList();
    }

    /** Permissions granted through the user's active roles. */
    public Mono<List<Permission>> permissionsOf(UUID userId) {
        return db.sql("""
                SELECT DISTINCT p.code FROM roles r
                JOIN users u ON r.code = ANY(u.roles)
                CROSS JOIN unnest(r.permissions) AS p(code)
                WHERE u.id = :userId AND r.is_active = true
                """)
                .bind("userId", userId)
                .map(row -> new Permission(row.get("code", String.class)))
                .all()
                .collectList();
    }

    public Mono<CustomerRecord> customerRecord(UUID userId) {
        return db.sql("""
                SELECT company_name, telegram, preferred_contact_method,
                       address, city, province, postal_code, country
                FROM users WHERE id = :userId
                """)
                .bind("userId", userId)
                .map(row -> new CustomerRecord(
                        row.get("company_name", String.class),
                        row.get("telegram", String.class),
                        row.get("preferred_contact_method", String.class),
                        row.get("address", String.class),
                        row.get("city", String.class),
                        row.get("province", String.class),
                        row.get("postal_code", String.class),
                        row.get("country", String.class)))
                .one();
    }

    public Mono<UUID> insertCustomer(String name, String email, String phone, String passwordHash, String companyName) {
        var spec = db.sql("""
                INSERT INTO users (name, email, phone, password_hash, roles, company_name)
                VALUES (:name, :email, :phone, :passwordHash, '{ROLE_CUSTOMER}', :companyName)
                RETURNING id
                """)
                .bind("name", name)
                .bind("email", email)
                .bind("phone", phone)
                .bind("passwordHash", passwordHash);
        spec = companyName == null
                ? spec.bindNull("companyName", String.class)
                : spec.bind("companyName", companyName);
        return spec
                .map(row -> row.get("id", UUID.class))
                .one();
    }

    public Mono<Void> touchLastLogin(UUID userId) {
        return db.sql("UPDATE users SET last_login_at = now() WHERE id = :id")
                .bind("id", userId)
                .then();
    }

    public Mono<Void> markEmailVerified(UUID userId) {
        return db.sql("UPDATE users SET email_verified_at = now() WHERE id = :id AND email_verified_at IS NULL")
                .bind("id", userId)
                .then();
    }

    /**
     * An invited member of staff becomes active by setting their password from
     * the invitation link, which also proves the address is theirs.
     */
    public Mono<Void> activateInvited(UUID userId) {
        return db.sql("""
                UPDATE users SET status = 'active', email_verified_at = coalesce(email_verified_at, now())
                WHERE id = :id AND status = 'invited'
                """)
                .bind("id", userId)
                .then();
    }

    public Mono<Void> setPasswordHash(UUID userId, String passwordHash) {
        return db.sql("UPDATE users SET password_hash = :hash WHERE id = :id")
                .bind("hash", passwordHash)
                .bind("id", userId)
                .then();
    }

    private static User toUser(Readable row) {
        User user = new User();
        user.setId(row.get("id", UUID.class));
        user.setName(row.get("name", String.class));
        user.setEmail(row.get("email", String.class));
        user.setPhone(row.get("phone", String.class));
        user.setPasswordHash(row.get("password_hash", String.class));
        user.setStatus(row.get("status", String.class));
        user.setEmailVerifiedAt(instant(row, "email_verified_at"));
        user.setLastLoginAt(instant(row, "last_login_at"));
        user.setCreatedAt(instant(row, "created_at"));
        return user;
    }

    private static Instant instant(Readable row, String column) {
        OffsetDateTime value = row.get(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record CustomerRecord(
            String companyName,
            String telegram,
            String preferredContactMethod,
            String billingAddress,
            String billingCity,
            String billingProvince,
            String billingPostalCode,
            String billingCountry) {
    }
}
