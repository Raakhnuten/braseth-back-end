package com.braseth.ecommerce.modules.user.entity;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.braseth.ecommerce.common.entity.BaseEntity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("roles")
public class Role extends BaseEntity {

    @Column("name")
    private String name;

    @Column("code")
    private String code;

    @Column("description")
    private String description;

    @Column("is_active")
    private boolean active;
}