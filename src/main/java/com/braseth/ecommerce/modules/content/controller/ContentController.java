package com.braseth.ecommerce.modules.content.controller;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.braseth.ecommerce.modules.content.service.ContentService;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Public site content: settings, services and portfolio. No sign-in needed.
 * The language comes from {@code Accept-Language} (the web app sends en or
 * km on every request); responses vary by it and may be cached briefly.
 */
@RestController
@RequiredArgsConstructor
public class ContentController {

    private static final CacheControl SHORT = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final ContentService content;

    @GetMapping("/api/v1/site-config")
    public Mono<ResponseEntity<JsonNode>> siteConfig(@RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String language) {
        return content.siteConfig(locale(language)).map(this::cached);
    }

    @GetMapping("/api/v1/services")
    public Mono<ResponseEntity<JsonNode>> services(@RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String language) {
        return content.services(locale(language)).map(this::cached);
    }

    @GetMapping("/api/v1/services/{slug}")
    public Mono<ResponseEntity<JsonNode>> service(@PathVariable String slug,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String language) {
        return content.service(slug, locale(language)).map(this::cached);
    }

    @GetMapping("/api/v1/portfolio")
    public Mono<ResponseEntity<JsonNode>> portfolio(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String language) {
        return content.portfolio(category, search, page, size, locale(language)).map(this::cached);
    }

    @GetMapping("/api/v1/portfolio/{slug}")
    public Mono<ResponseEntity<JsonNode>> project(@PathVariable String slug,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String language) {
        return content.project(slug, locale(language)).map(this::cached);
    }

    /** Khmer when asked for first; English otherwise. */
    private static String locale(String acceptLanguage) {
        return acceptLanguage != null && acceptLanguage.trim().toLowerCase().startsWith("km") ? "km" : "en";
    }

    private ResponseEntity<JsonNode> cached(JsonNode body) {
        return ResponseEntity.ok()
                .cacheControl(SHORT)
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE)
                .body(body);
    }
}
