package com.braseth.ecommerce.modules.staff.dto;

import java.util.List;

/** The signed-in member of staff, with the web app's role and permission codes. */
public record StaffMember(String id, String fullName, String email, List<String> roles, List<String> permissions) {
}
