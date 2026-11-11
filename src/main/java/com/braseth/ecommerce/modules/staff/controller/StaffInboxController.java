package com.braseth.ecommerce.modules.staff.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.braseth.ecommerce.modules.staff.service.StaffActivityService;
import com.braseth.ecommerce.modules.staff.service.StaffGate;
import com.braseth.ecommerce.modules.staff.service.StaffMessagesService;
import com.braseth.ecommerce.modules.staff.service.StaffReportsService;
import com.braseth.ecommerce.modules.staff.service.StaffSettingsService;
import com.braseth.ecommerce.modules.staff.service.StaffViews;
import com.braseth.ecommerce.security.jwt.CustomUserDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * The quieter staff screens: the contact inbox (messages.handle), the
 * newsletter list (newsletter.view), site settings (settings.edit) and the
 * activity log (activity.view) and sales reports (reports.view).
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class StaffInboxController {

    private final StaffGate gate;
    private final StaffMessagesService messages;
    private final StaffSettingsService settings;
    private final StaffActivityService activity;
    private final StaffReportsService reports;

    // ---- Messages -----------------------------------------------------------

    @GetMapping("/messages")
    public Mono<ObjectNode> messages(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) @Pattern(regexp = "new|open|replied|archived") String filter,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return gate.require(me, "messages.handle").then(Mono.defer(() -> messages.list(filter, q, page, size)));
    }

    @GetMapping("/messages/{id}")
    public Mono<ObjectNode> message(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return gate.require(me, "messages.handle").then(Mono.defer(() -> messages.detail(StaffViews.uuid(id, "message"))));
    }

    @PostMapping("/messages/{id}/status")
    public Mono<ObjectNode> messageStatus(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @Valid @RequestBody MessageStatusBody body) {
        return gate.require(me, "messages.handle")
                .flatMap(staff -> messages.setStatus(staff, StaffViews.uuid(id, "message"), body.status()));
    }

    public record MessageStatusBody(@NotNull @Pattern(regexp = "new|read|replied|archived") String status) {
    }

    // ---- Newsletter ---------------------------------------------------------

    @GetMapping("/newsletter-subscribers")
    public Mono<ObjectNode> subscribers(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) @Pattern(regexp = "subscribed|unsubscribed") String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return gate.require(me, "newsletter.view").then(Mono.defer(() -> messages.subscribers(status, q, page, size)));
    }

    @GetMapping("/newsletter-subscribers/export")
    public Mono<ResponseEntity<String>> exportSubscribers(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) @Pattern(regexp = "subscribed|unsubscribed") String status,
            @RequestParam(required = false) String q) {
        return gate.require(me, "newsletter.view")
                .then(Mono.defer(() -> messages.exportSubscribers(status, q)))
                .map(csv -> csv(csv, "newsletter-subscribers.csv"));
    }

    // ---- Site settings ------------------------------------------------------

    @GetMapping("/site-settings")
    public Mono<ObjectNode> settings(@AuthenticationPrincipal CustomUserDetails me) {
        return gate.require(me, "settings.edit").then(Mono.defer(settings::load));
    }

    @PutMapping("/site-settings/{section}")
    public Mono<ObjectNode> saveSettings(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String section,
            @RequestBody JsonNode body) {
        return gate.require(me, "settings.edit").flatMap(staff -> settings.save(staff, section, body));
    }

    // ---- Activity log -------------------------------------------------------

    @GetMapping("/activity")
    public Mono<ObjectNode> activity(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) String subjectId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return gate.require(me, "activity.view").then(Mono.defer(() -> {
            UUID subject = subjectId == null || subjectId.isBlank() ? null : StaffViews.uuid(subjectId, "record");
            return activity.list(area, subject, q, from, to, page, size);
        }));
    }

    // ---- Reports ------------------------------------------------------------

    /** {@code from} and {@code to} are months, "YYYY-MM". */
    @GetMapping("/reports/sales")
    public Mono<ObjectNode> sales(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return gate.require(me, "reports.view").then(Mono.defer(() -> reports.sales(reports.range(from, to))));
    }

    @GetMapping("/reports/sales/export")
    public Mono<ResponseEntity<String>> exportSales(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam String section) {
        return gate.require(me, "reports.view")
                .then(Mono.defer(() -> reports.export(reports.range(from, to), section)))
                .map(csv -> csv(csv, "sales-" + section + ".csv"));
    }

    /** A CSV download; the web app saves the text under its own file name. */
    static ResponseEntity<String> csv(String body, String fileName) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(body);
    }
}
