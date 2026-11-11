package com.braseth.ecommerce.modules.staff.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.braseth.ecommerce.modules.staff.dto.StaffBodies.AccountStatusChange;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.DesignReview;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.OrderNote;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.StaffInvite;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.StaffRoles;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.StaffStatus;
import com.braseth.ecommerce.modules.staff.service.StaffAccountsService;
import com.braseth.ecommerce.modules.staff.service.StaffCustomersService;
import com.braseth.ecommerce.modules.staff.service.StaffDesignsService;
import com.braseth.ecommerce.modules.staff.service.StaffGate;
import com.braseth.ecommerce.modules.staff.service.StaffViews;
import com.braseth.ecommerce.security.jwt.CustomUserDetails;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Customer accounts (customers.view; suspending needs customers.manage),
 * the artwork queue (designs.review) and the staff list (staff.manage).
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class StaffPeopleController {

    private final StaffGate gate;
    private final StaffCustomersService customers;
    private final StaffDesignsService designs;
    private final StaffAccountsService staff;

    // ---- Customers ----------------------------------------------------------

    @GetMapping("/customers")
    public Mono<ObjectNode> customers(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return gate.require(me, "customers.view").then(Mono.defer(() -> customers.list(kind, status, q, page, size)));
    }

    @GetMapping("/customers/{id}")
    public Mono<ObjectNode> customer(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return gate.require(me, "customers.view")
                .then(Mono.defer(() -> customers.detail(StaffViews.uuid(id, "customer"))));
    }

    @PostMapping("/customers/{id}/status")
    public Mono<ObjectNode> customerStatus(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @Valid @RequestBody AccountStatusChange change) {
        return gate.require(me, "customers.manage")
                .flatMap(actor -> customers.changeStatus(actor, StaffViews.uuid(id, "customer"), change));
    }

    @PostMapping("/customers/{id}/password-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> passwordReset(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return gate.require(me, "customers.view")
                .flatMap(actor -> customers.sendPasswordReset(actor, StaffViews.uuid(id, "customer")));
    }

    @PostMapping("/customers/{id}/notes")
    public Mono<ObjectNode> customerNote(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @Valid @RequestBody OrderNote note) {
        return gate.require(me, "customers.view")
                .flatMap(actor -> customers.addNote(actor, StaffViews.uuid(id, "customer"), note));
    }

    // ---- Artwork ------------------------------------------------------------

    @GetMapping("/designs")
    public Mono<ObjectNode> designs(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return gate.require(me, "designs.review").then(Mono.defer(() -> designs.list(status, q, page, size)));
    }

    @GetMapping("/designs/{id}")
    public Mono<ObjectNode> design(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return gate.require(me, "designs.review").then(Mono.defer(() -> designs.detail(StaffViews.uuid(id, "file"))));
    }

    @PostMapping("/designs/{id}/review")
    public Mono<ObjectNode> review(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @Valid @RequestBody DesignReview decision) {
        return gate.require(me, "designs.review")
                .flatMap(actor -> designs.review(actor, StaffViews.uuid(id, "file"), decision));
    }

    // ---- Staff --------------------------------------------------------------

    @GetMapping("/staff")
    public Mono<ArrayNode> staff(@AuthenticationPrincipal CustomUserDetails me) {
        return gate.require(me, "staff.manage").then(Mono.defer(staff::list));
    }

    @PostMapping("/staff")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ObjectNode> invite(@AuthenticationPrincipal CustomUserDetails me, @Valid @RequestBody StaffInvite invite) {
        return gate.require(me, "staff.manage").flatMap(actor -> staff.invite(actor, invite));
    }

    @PutMapping("/staff/{id}/roles")
    public Mono<ObjectNode> roles(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @Valid @RequestBody StaffRoles body) {
        return gate.require(me, "staff.manage")
                .flatMap(actor -> staff.setRoles(actor, StaffViews.uuid(id, "member of staff"), body.roles()));
    }

    @PostMapping("/staff/{id}/status")
    public Mono<ObjectNode> staffStatus(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @Valid @RequestBody StaffStatus body) {
        return gate.require(me, "staff.manage")
                .flatMap(actor -> staff.setStatus(actor, StaffViews.uuid(id, "member of staff"), body.status()));
    }
}
