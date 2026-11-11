package com.braseth.ecommerce.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import com.braseth.ecommerce.support.IntegrationTest;

class SecurityIT extends IntegrationTest {

    @Test
    void publicReadsNeedNoToken() {
        http.get().uri("/api/v1/products").exchange().expectStatus().isOk();
        http.get().uri("/api/v1/site-config").exchange().expectStatus().isOk();
        http.get().uri("/api/v1/designs/upload-policy").exchange().expectStatus().isOk();
    }

    @Test
    void protectedEndpointsAnswer401WithAJsonBody() {
        http.get().uri("/api/v1/auth/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("unauthenticated");
    }

    @Test
    void anInvalidTokenIsTreatedAsSignedOut() {
        http.get().uri("/api/v1/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void aValidTokenSignsTheUserIn() {
        http.get().uri("/api/v1/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken("customer@braseth.test"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.email").isEqualTo("customer@braseth.test");
    }

    @Test
    void theRemovedLegacyApiAndOtherPathsAreClosed() {
        http.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        http.get().uri("/anything-else").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void healthAndApiDocsAreOpen() {
        http.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        http.get().uri("/v3/api-docs").exchange().expectStatus().isOk();
    }

    @Test
    void frameworkErrorsKeepTheirStatusAndUseTheProblemShape() {
        String token = accessToken("customer@braseth.test");
        http.get().uri("/api/v1/no-such-endpoint")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("not_found");
        http.delete().uri("/api/v1/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectBody().jsonPath("$.code").isEqualTo("method_not_allowed");
    }
}
