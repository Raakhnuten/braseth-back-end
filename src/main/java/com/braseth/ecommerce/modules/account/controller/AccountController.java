package com.braseth.ecommerce.modules.account.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.modules.account.dto.AccountBodies.CompanyBody;
import com.braseth.ecommerce.modules.account.dto.AccountBodies.DecisionBody;
import com.braseth.ecommerce.modules.account.dto.AccountBodies.ProfileBody;
import com.braseth.ecommerce.modules.account.dto.AccountBodies.SettingsBody;
import com.braseth.ecommerce.modules.account.service.AccountService;
import com.braseth.ecommerce.modules.account.service.MyNotificationsService;
import com.braseth.ecommerce.modules.account.service.MyOrdersService;
import com.braseth.ecommerce.modules.account.service.MyQuotesService;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.CustomerView;
import com.braseth.ecommerce.modules.design.service.DesignService;
import com.braseth.ecommerce.modules.design.service.DesignService.DesignView;
import com.braseth.ecommerce.security.jwt.CustomUserDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * The customer area: everything under /api/v1/me. Sign-in required (the v1
 * security chain), and every read and write is scoped to the signed-in user,
 * so someone else's quote, order or file is simply not found.
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService account;
    private final MyQuotesService quotes;
    private final MyOrdersService orders;
    private final MyNotificationsService notifications;
    private final DesignService designs;
    private final ObjectMapper json;

    @GetMapping("/dashboard")
    public Mono<ObjectNode> dashboard(@AuthenticationPrincipal CustomUserDetails me) {
        return account.dashboard(me.getUserId());
    }

    @GetMapping("/profile")
    public Mono<CustomerView> profile(@AuthenticationPrincipal CustomUserDetails me) {
        return account.profile(me.getUserId());
    }

    @PutMapping("/profile")
    public Mono<CustomerView> updateProfile(@AuthenticationPrincipal CustomUserDetails me, @Valid @RequestBody ProfileBody body) {
        return account.updateProfile(me.getUserId(), body);
    }

    @GetMapping("/company")
    public Mono<JsonNode> company(@AuthenticationPrincipal CustomUserDetails me) {
        return account.company(me.getUserId());
    }

    @PutMapping("/company")
    public Mono<JsonNode> updateCompany(@AuthenticationPrincipal CustomUserDetails me, @Valid @RequestBody CompanyBody body) {
        return account.updateCompany(me.getUserId(), body);
    }

    @GetMapping("/settings")
    public Mono<ObjectNode> settings(@AuthenticationPrincipal CustomUserDetails me) {
        return account.settings(me.getUserId());
    }

    @PutMapping("/settings")
    public Mono<ObjectNode> updateSettings(@AuthenticationPrincipal CustomUserDetails me, @Valid @RequestBody SettingsBody body) {
        return account.updateSettings(me.getUserId(), body);
    }

    // ---- Quotes -------------------------------------------------------------

    @GetMapping("/quotes")
    public Mono<ObjectNode> quotes(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return quotes.list(me.getUserId(), status, page, size);
    }

    @GetMapping("/quotes/{id}")
    public Mono<ObjectNode> quote(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return quotes.detail(me.getUserId(), uuid(id, "quote"));
    }

    @PostMapping("/quotes/{id}/decision")
    public Mono<ObjectNode> decide(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @Valid @RequestBody DecisionBody body) {
        return quotes.decide(me.getUserId(), uuid(id, "quote"), body.accepted(), body.note());
    }

    // ---- Orders -------------------------------------------------------------

    @GetMapping("/orders")
    public Mono<ObjectNode> orders(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return orders.list(me.getUserId(), status, search, page, size);
    }

    @GetMapping("/orders/{id}")
    public Mono<ObjectNode> order(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return orders.detail(me.getUserId(), uuid(id, "order"));
    }

    @GetMapping("/orders/{id}/tracking")
    public Mono<ArrayNode> tracking(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return orders.tracking(me.getUserId(), uuid(id, "order"));
    }

    // ---- Artwork library ----------------------------------------------------

    @GetMapping("/designs")
    public Mono<ObjectNode> designs(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "24") int size) {
        return designs.listMine(me.getUserId(), page, size, json);
    }

    @GetMapping("/designs/{id}")
    public Mono<DesignView> design(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return designs.mine(me.getUserId(), uuid(id, "file"));
    }

    @DeleteMapping("/designs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> removeDesign(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return designs.removeMine(me.getUserId(), uuid(id, "file"));
    }

    // ---- Notifications ------------------------------------------------------

    @GetMapping("/notifications")
    public Mono<ObjectNode> notifications(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return notifications.list(me.getUserId(), page, size);
    }

    @PostMapping("/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> markRead(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return notifications.markRead(me.getUserId(), uuid(id, "notification"));
    }

    @PostMapping("/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> markAllRead(@AuthenticationPrincipal CustomUserDetails me) {
        return notifications.markAllRead(me.getUserId());
    }

    /** A malformed id reads as not found, like any id that is not the customer's. */
    private static UUID uuid(String value, String thing) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "not_found", "We could not find that " + thing + ".");
        }
    }
}
