package com.braseth.ecommerce.modules.staff.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.braseth.ecommerce.modules.staff.dto.StaffBodies.OrderNote;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.OrderSchedule;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.OrderStatusChange;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.RecordedPayment;
import com.braseth.ecommerce.modules.staff.service.StaffGate;
import com.braseth.ecommerce.modules.staff.service.StaffOrdersService;
import com.braseth.ecommerce.modules.staff.service.StaffViews;
import com.braseth.ecommerce.security.jwt.CustomUserDetails;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Orders from the staff side. Reading needs orders.view; stage, schedule
 * and notes need orders.progress; payments need orders.payments.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@RequiredArgsConstructor
public class StaffOrdersController {

    private final StaffGate gate;
    private final StaffOrdersService orders;

    @GetMapping
    public Mono<ObjectNode> list(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return gate.require(me, "orders.view").then(Mono.defer(() -> orders.list(status, paymentStatus, q, page, size)));
    }

    @GetMapping("/{id}")
    public Mono<ObjectNode> detail(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return gate.require(me, "orders.view").then(Mono.defer(() -> orders.detail(StaffViews.uuid(id, "order"))));
    }

    @PostMapping("/{id}/status")
    public Mono<ObjectNode> status(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @Valid @RequestBody OrderStatusChange change) {
        return gate.require(me, "orders.progress")
                .flatMap(staff -> orders.changeStatus(staff, StaffViews.uuid(id, "order"), change));
    }

    @PostMapping("/{id}/payments")
    public Mono<ObjectNode> payment(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @Valid @RequestBody RecordedPayment payment) {
        return gate.require(me, "orders.payments")
                .flatMap(staff -> orders.recordPayment(staff, StaffViews.uuid(id, "order"), payment));
    }

    @PutMapping("/{id}/schedule")
    public Mono<ObjectNode> schedule(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @Valid @RequestBody OrderSchedule update) {
        return gate.require(me, "orders.progress")
                .flatMap(staff -> orders.updateSchedule(staff, StaffViews.uuid(id, "order"), update));
    }

    @PostMapping("/{id}/notes")
    public Mono<ObjectNode> note(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @Valid @RequestBody OrderNote note) {
        return gate.require(me, "orders.progress")
                .flatMap(staff -> orders.addNote(staff, StaffViews.uuid(id, "order"), note));
    }
}
