package com.braseth.ecommerce.modules.catalogue.controller;

import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.braseth.ecommerce.modules.catalogue.dto.CatalogueViews.Category;
import com.braseth.ecommerce.modules.catalogue.dto.CatalogueViews.Page;
import com.braseth.ecommerce.modules.catalogue.dto.CatalogueViews.Product;
import com.braseth.ecommerce.modules.catalogue.dto.CatalogueViews.ProductSummary;
import com.braseth.ecommerce.modules.catalogue.service.CatalogueService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * The public catalogue: no sign-in needed, no prices ever. Responses may be
 * cached briefly, so a busy page does not query the database per visitor.
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class CatalogueController {

    private static final CacheControl SHORT = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final CatalogueService catalogue;

    @GetMapping
    public Mono<ResponseEntity<Page<ProductSummary>>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "false") boolean featured,
            @RequestParam(required = false) String printMethod,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size) {
        return catalogue.list(search, category, featured, printMethod, sort, page, size).map(this::cached);
    }

    @GetMapping("/categories")
    public Mono<ResponseEntity<List<Category>>> categories() {
        return catalogue.categories().map(this::cached);
    }

    @GetMapping("/best-sellers")
    public Mono<ResponseEntity<Page<ProductSummary>>> bestSellers(@RequestParam(defaultValue = "8") int size) {
        return catalogue.bestSellers(size).map(this::cached);
    }

    @GetMapping("/{slug}")
    public Mono<ResponseEntity<Product>> product(@PathVariable String slug) {
        return catalogue.bySlug(slug).map(this::cached);
    }

    @GetMapping("/{slug}/related")
    public Mono<ResponseEntity<List<ProductSummary>>> related(@PathVariable String slug) {
        return catalogue.related(slug).map(this::cached);
    }

    private <T> ResponseEntity<T> cached(T body) {
        return ResponseEntity.ok().cacheControl(SHORT).body(body);
    }
}
