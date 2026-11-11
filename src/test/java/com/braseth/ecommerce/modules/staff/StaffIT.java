package com.braseth.ecommerce.modules.staff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.braseth.ecommerce.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;

/** The staff area: who may enter, what each role may do, and the alert bell. */
class StaffIT extends IntegrationTest {

    @Test
    void aCustomerIsNotStaff() {
        http.get().uri("/api/v1/admin/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken("customer@braseth.test"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("not_staff");
    }

    @Test
    void theOwnerSeesTheirRolesAndPermissions() {
        http.get().uri("/api/v1/admin/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken("owner@braseth.test"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.fullName").isEqualTo("Dev Owner")
                .jsonPath("$.roles[0]").isEqualTo("owner")
                .jsonPath("$.permissions").isNotEmpty();
    }

    @Test
    void onlyTheAdminSeesTheStaffArea() {
        http.get().uri("/api/v1/admin/quotes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken("owner@braseth.test"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.totalPages").exists();

        http.get().uri("/api/v1/admin/quotes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken("customer@braseth.test"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("not_staff");
    }

    @Test
    void staffCanOnlyBeInvitedAsAdmin() {
        http.post().uri("/api/v1/admin/staff")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken("owner@braseth.test"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"fullName":"IT Sales","email":"it-sales-%s@example.com","roles":["sales"]}
                        """.formatted(UUID.randomUUID()))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void aContactMessageRaisesAnAlertThatCanBeMarkedRead() {
        String subject = "Alert test " + UUID.randomUUID();
        http.post().uri("/api/v1/contact-messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"IT Visitor","email":"visitor@example.com","subject":"%s","message":"Hello"}
                        """.formatted(subject))
                .exchange()
                .expectStatus().isCreated();

        String owner = accessToken("owner@braseth.test");
        JsonNode alert = findAlert(owner, subject);
        assertThat(alert.get("read").asBoolean()).isFalse();

        http.post().uri("/api/v1/admin/notifications/{id}/read", alert.get("id").asText())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner)
                .exchange()
                .expectStatus().isNoContent();

        assertThat(findAlert(owner, subject).get("read").asBoolean()).isTrue();
    }

    @Test
    void alertsArePagedWithAnUnreadCount() {
        http.get().uri("/api/v1/admin/notifications?page=1&size=5")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken("owner@braseth.test"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.page").isEqualTo(1)
                .jsonPath("$.size").isEqualTo(5)
                .jsonPath("$.totalItems").exists()
                .jsonPath("$.unreadCount").exists();
    }

    @Test
    void markingAnUnknownAlertIsA404() {
        String owner = accessToken("owner@braseth.test");
        for (String id : new String[] { "not-a-uuid", UUID.randomUUID().toString() }) {
            http.post().uri("/api/v1/admin/notifications/{id}/read", id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner)
                    .exchange()
                    .expectStatus().isNotFound();
        }
    }

    private JsonNode findAlert(String token, String subject) {
        JsonNode page = http.get().uri("/api/v1/admin/notifications?size=50")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        return StreamSupport.stream(page.get("items").spliterator(), false)
                .filter(item -> item.path("body").asText().contains(subject))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No alert mentions " + subject));
    }
}
