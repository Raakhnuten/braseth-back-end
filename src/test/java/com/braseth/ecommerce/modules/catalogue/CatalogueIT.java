package com.braseth.ecommerce.modules.catalogue;

import org.junit.jupiter.api.Test;

import com.braseth.ecommerce.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;

/** The public catalogue reads, against the local sample catalogue (V003). */
class CatalogueIT extends IntegrationTest {

    @Test
    void productsArePaged() {
        http.get().uri("/api/v1/products?page=1&size=2")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items").isNotEmpty()
                .jsonPath("$.page").isEqualTo(1)
                .jsonPath("$.size").isEqualTo(2)
                .jsonPath("$.totalItems").exists();
    }

    @Test
    void aProductCanBeReadBySlug() {
        String slug = http.get().uri("/api/v1/products?size=1")
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .get("items").get(0).get("slug").asText();

        http.get().uri("/api/v1/products/{slug}", slug)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.slug").isEqualTo(slug);
    }

    @Test
    void anUnknownProductIsA404() {
        http.get().uri("/api/v1/products/no-such-product")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void categoriesAreListed() {
        http.get().uri("/api/v1/products/categories")
                .exchange()
                .expectStatus().isOk();
    }
}
