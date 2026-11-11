package com.braseth.ecommerce.modules.staff.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.PricingDraft;
import com.braseth.ecommerce.modules.staff.dto.StaffBodies.RecordedDecision;
import com.braseth.ecommerce.modules.staff.service.StaffGate;
import com.braseth.ecommerce.modules.staff.service.StaffOverviewService;
import com.braseth.ecommerce.modules.staff.service.StaffQuotesService;
import com.braseth.ecommerce.security.jwt.CustomUserDetails;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * The staff overview and quote queue. Reading needs quotes.view; pricing,
 * sending, recording an answer and cancelling need quotes.price — the same
 * split the web app's staff screens gate their buttons on.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class StaffQuotesController {

    private final StaffGate gate;
    private final StaffQuotesService quotes;
    private final StaffOverviewService overview;

    @GetMapping("/overview")
    public Mono<ObjectNode> overview(@AuthenticationPrincipal CustomUserDetails me) {
        return gate.require(me, null).then(overview.overview());
    }

    @GetMapping("/quotes")
    public Mono<ObjectNode> list(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return gate.require(me, "quotes.view").then(quotes.list(status, q, page, size));
    }

    @GetMapping("/quotes/{id}")
    public Mono<ObjectNode> detail(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return gate.require(me, "quotes.view").then(quotes.detail(uuid(id)));
    }

    @PutMapping("/quotes/{id}/pricing")
    public Mono<ObjectNode> price(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @Valid @RequestBody PricingDraft draft) {
        return gate.require(me, "quotes.price").flatMap(staff -> quotes.price(staff, uuid(id), draft));
    }

    @PostMapping("/quotes/{id}/send")
    public Mono<ObjectNode> send(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return gate.require(me, "quotes.price").flatMap(staff -> quotes.send(staff, uuid(id)));
    }

    @PostMapping("/quotes/{id}/decision")
    public Mono<ObjectNode> decision(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @Valid @RequestBody RecordedDecision decision) {
        return gate.require(me, "quotes.price").flatMap(staff -> quotes.recordDecision(staff, uuid(id), decision));
    }

    @PostMapping("/quotes/{id}/cancel")
    public Mono<ObjectNode> cancel(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @Valid @RequestBody(required = false) CancelBody body) {
        String reason = body == null ? null : body.reason();
        return gate.require(me, "quotes.price").flatMap(staff -> quotes.cancel(staff, uuid(id), reason));
    }

    /** Why staff cancelled; kept in the activity log, not shown to the customer. */
    public record CancelBody(@Size(max = 500) String reason) {
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "not_found", "We could not find that quote.");
        }
    }
}
