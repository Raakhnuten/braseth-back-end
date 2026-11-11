package com.braseth.ecommerce.modules.staff.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.braseth.ecommerce.modules.staff.dto.StaffMember;
import com.braseth.ecommerce.modules.staff.service.StaffProfileService;
import com.braseth.ecommerce.security.jwt.CustomUserDetails;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Who the signed-in staff member is, and what they may do. The staff area of
 * the web app calls this before showing anything; a customer account gets 403.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class StaffController {

    private final StaffProfileService profiles;

    @GetMapping("/me")
    public Mono<StaffMember> me(@AuthenticationPrincipal CustomUserDetails principal) {
        return profiles.profile(principal.getUserId());
    }
}
