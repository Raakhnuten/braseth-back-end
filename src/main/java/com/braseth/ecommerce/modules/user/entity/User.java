package com.braseth.ecommerce.modules.user.entity;

import java.time.Instant;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.braseth.ecommerce.common.entity.BaseEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor

@Table("users")
public class User extends BaseEntity {

    @Column("name")
    private String name;

    @Column("email")
    private String email;

    @Column("phone")
    private String phone;

    @Column("password_hash")
    private String passwordHash;

    @Column("status")
    private String status;

    @Column("email_verified_at")
    private Instant emailVerifiedAt;

    @Column("last_login_at")
    private Instant lastLoginAt;

    @Column("deleted_at")
    private Instant deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean isActive() {
        return "active".equals(status) && !isDeleted();
    }

    public boolean isEnabled() {
        return isActive() && isEmailVerified();
    }
}