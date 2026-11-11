package com.braseth.ecommerce.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Base for integration tests: the whole application on a random port against
 * one PostgreSQL container, migrated with the same changelog as the local
 * profile (including the dev accounts from V003).
 *
 * The container is started once and shared by every test class; Testcontainers
 * removes it when the JVM exits. Tests that write data use their own unique
 * values, so they do not depend on each other or on order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    /** Password of every seeded dev account (V003). */
    protected static final String DEV_PASSWORD = "Braseth-Dev-2026!";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("braseth_test")
            .withUsername("braseth")
            .withPassword("braseth");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String hostAndPort = POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + hostAndPort + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected WebTestClient http;

    /** Signs in and returns the session body: accessToken, refreshToken, expiresAt, customer. */
    protected JsonNode signIn(String email, String password) {
        return http.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    /** An access token for a seeded dev account, e.g. {@code owner@braseth.test}. */
    protected String accessToken(String email) {
        return signIn(email, DEV_PASSWORD).get("accessToken").asText();
    }
}
