package com.braseth.ecommerce.modules.staff.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.braseth.ecommerce.modules.staff.service.StaffAlertsService;
import com.braseth.ecommerce.modules.staff.service.StaffGate;
import com.braseth.ecommerce.security.jwt.CustomUserDetails;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/** The staff alert bell (StaffNotificationPage); see {@link StaffAlertsService}. */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@RequiredArgsConstructor
public class StaffAlertsController {

    private final StaffGate gate;
    private final StaffAlertsService alerts;

    @GetMapping
    public Mono<ObjectNode> list(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return gate.require(me, null).then(Mono.defer(() -> alerts.list(me, page, size)));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> markRead(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return gate.require(me, null).then(Mono.defer(() -> alerts.markRead(me, id)));
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> markAllRead(@AuthenticationPrincipal CustomUserDetails me) {
        return gate.require(me, null).then(Mono.defer(() -> alerts.markAllRead(me)));
    }
}
