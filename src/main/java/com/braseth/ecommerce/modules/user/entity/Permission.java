package com.braseth.ecommerce.modules.user.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A permission code a role grants (an entry of roles.permissions), e.g. "quotes.view". */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Permission {

    private String code;
}
