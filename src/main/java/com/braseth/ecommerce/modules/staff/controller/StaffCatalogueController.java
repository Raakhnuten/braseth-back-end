package com.braseth.ecommerce.modules.staff.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.braseth.ecommerce.modules.staff.service.StaffCatalogueService;
import com.braseth.ecommerce.modules.staff.service.StaffContentService;
import com.braseth.ecommerce.modules.staff.service.StaffGate;
import com.braseth.ecommerce.modules.staff.service.StaffMediaService;
import com.braseth.ecommerce.modules.staff.service.StaffViews;
import com.braseth.ecommerce.security.jwt.CustomUserDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * What the public site shows: products, categories, services, the portfolio
 * and their photos. Everything here needs catalogue.edit.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class StaffCatalogueController {

    private static final String EDIT = "catalogue.edit";

    private final StaffGate gate;
    private final StaffCatalogueService catalogue;
    private final StaffContentService content;
    private final StaffMediaService media;

    // ---- Products -----------------------------------------------------------

    @GetMapping("/products")
    public Mono<ObjectNode> products(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return gate.require(me, EDIT).then(Mono.defer(() -> catalogue.products(categoryId, status, q, page, size)));
    }

    @GetMapping("/products/{id}")
    public Mono<ObjectNode> product(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return gate.require(me, EDIT).then(Mono.defer(() -> catalogue.product(StaffViews.uuid(id, "product"))));
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ObjectNode> createProduct(@AuthenticationPrincipal CustomUserDetails me, @RequestBody JsonNode body) {
        return gate.require(me, EDIT).flatMap(staff -> catalogue.createProduct(staff, body));
    }

    @PutMapping("/products/{id}")
    public Mono<ObjectNode> updateProduct(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @RequestBody JsonNode body) {
        return gate.require(me, EDIT).flatMap(staff -> catalogue.updateProduct(staff, StaffViews.uuid(id, "product"), body));
    }

    // ---- Categories ---------------------------------------------------------

    @GetMapping("/categories")
    public Mono<ArrayNode> categories(@AuthenticationPrincipal CustomUserDetails me) {
        return gate.require(me, EDIT).then(Mono.defer(catalogue::categories));
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ObjectNode> createCategory(@AuthenticationPrincipal CustomUserDetails me, @RequestBody JsonNode body) {
        return gate.require(me, EDIT).flatMap(staff -> catalogue.createCategory(staff, body));
    }

    @PutMapping("/categories/{id}")
    public Mono<ObjectNode> updateCategory(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @RequestBody JsonNode body) {
        return gate.require(me, EDIT).flatMap(staff -> catalogue.updateCategory(staff, StaffViews.uuid(id, "category"), body));
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteCategory(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return gate.require(me, EDIT).flatMap(staff -> catalogue.deleteCategory(staff, StaffViews.uuid(id, "category")));
    }

    // ---- Services -----------------------------------------------------------

    @GetMapping("/services")
    public Mono<ArrayNode> services(@AuthenticationPrincipal CustomUserDetails me) {
        return gate.require(me, EDIT).then(Mono.defer(content::services));
    }

    /** PUT { ids }: the whole order at once. Mapped before /services/{id} reads "order" as an id. */
    @PutMapping("/services/order")
    public Mono<ArrayNode> orderServices(@AuthenticationPrincipal CustomUserDetails me,
            @Valid @RequestBody ServiceOrder body) {
        return gate.require(me, EDIT).flatMap(staff -> content.orderServices(staff, body.ids()));
    }

    public record ServiceOrder(@NotNull @Size(max = 200) List<String> ids) {
    }

    @GetMapping("/services/{id}")
    public Mono<ObjectNode> service(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return gate.require(me, EDIT).then(Mono.defer(() -> content.service(StaffViews.uuid(id, "service"))));
    }

    @PostMapping("/services")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ObjectNode> createService(@AuthenticationPrincipal CustomUserDetails me, @RequestBody JsonNode body) {
        return gate.require(me, EDIT).flatMap(staff -> content.createService(staff, body));
    }

    @PutMapping("/services/{id}")
    public Mono<ObjectNode> updateService(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @RequestBody JsonNode body) {
        return gate.require(me, EDIT).flatMap(staff -> content.updateService(staff, StaffViews.uuid(id, "service"), body));
    }

    // ---- Portfolio ----------------------------------------------------------

    @GetMapping("/portfolio")
    public Mono<ObjectNode> portfolio(@AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return gate.require(me, EDIT).then(Mono.defer(() -> content.portfolio(category, status, page, size)));
    }

    @GetMapping("/portfolio/{id}")
    public Mono<ObjectNode> project(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id) {
        return gate.require(me, EDIT).then(Mono.defer(() -> content.project(StaffViews.uuid(id, "project"))));
    }

    @PostMapping("/portfolio")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ObjectNode> createProject(@AuthenticationPrincipal CustomUserDetails me, @RequestBody JsonNode body) {
        return gate.require(me, EDIT).flatMap(staff -> content.createProject(staff, body));
    }

    @PutMapping("/portfolio/{id}")
    public Mono<ObjectNode> updateProject(@AuthenticationPrincipal CustomUserDetails me, @PathVariable String id,
            @RequestBody JsonNode body) {
        return gate.require(me, EDIT).flatMap(staff -> content.updateProject(staff, StaffViews.uuid(id, "project"), body));
    }

    // ---- Photos -------------------------------------------------------------

    @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ObjectNode> upload(@AuthenticationPrincipal CustomUserDetails me, @RequestPart("file") FilePart file) {
        return gate.require(me, EDIT).then(Mono.defer(() -> media.upload(file)));
    }
}
