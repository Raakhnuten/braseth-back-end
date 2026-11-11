package com.braseth.ecommerce.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec;

import com.braseth.ecommerce.common.util.SecureTokens;
import com.braseth.ecommerce.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;

/** The customer account flows, end to end over HTTP. */
class AuthIT extends IntegrationTest {

    private static final String PASSWORD = "Correct-Horse-9";

    /** A spy, so mail still goes to the log but tests can read the links' tokens. */
    @MockitoSpyBean
    AccountMail mail;

    @Autowired
    DatabaseClient db;

    @Test
    void wrongPasswordIsRejectedWithoutSayingWhy() {
        post("/api/v1/auth/login", "{\"email\":\"customer@braseth.test\",\"password\":\"wrong\"}")
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_credentials");
    }

    @Test
    void invalidInputGetsFieldErrors() {
        post("/api/v1/auth/login", "{\"email\":\"not-an-email\"}")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("validation")
                .jsonPath("$.errors.email").exists()
                .jsonPath("$.errors.password").exists();
    }

    @Test
    void refreshIssuesANewAccessTokenAndLogoutEndsTheSession() {
        JsonNode session = signIn("customer@braseth.test", DEV_PASSWORD);
        String refreshToken = session.get("refreshToken").asText();
        String body = "{\"refreshToken\":\"%s\"}".formatted(refreshToken);

        post("/api/v1/auth/refresh", body)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isNotEmpty()
                .jsonPath("$.refreshToken").isEqualTo(refreshToken);

        post("/api/v1/auth/logout", body).expectStatus().isNoContent();

        post("/api/v1/auth/refresh", body)
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("session_expired");
    }

    @Test
    void anAccessTokenCannotBeUsedToRefresh() {
        String access = accessToken("customer@braseth.test");
        post("/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(access))
                .expectStatus().isUnauthorized();
    }

    @Test
    void registerVerifyThenSignIn() {
        String email = uniqueEmail();
        register(email).expectStatus().isCreated()
                .expectBody().jsonPath("$.verificationRequired").isEqualTo(true);

        post("/api/v1/auth/login", login(email, PASSWORD))
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("email_not_verified");

        String token = verificationTokenSentTo(email);
        post("/api/v1/auth/verify-email", token(token)).expectStatus().isNoContent();

        assertThat(signIn(email, PASSWORD).get("customer").get("email").asText()).isEqualTo(email);
    }

    @Test
    void aVerificationLinkWorksOnlyOnce() {
        String email = uniqueEmail();
        register(email).expectStatus().isCreated();
        String token = verificationTokenSentTo(email);

        post("/api/v1/auth/verify-email", token(token)).expectStatus().isNoContent();
        post("/api/v1/auth/verify-email", token(token))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_token");
    }

    @Test
    void anUnknownVerificationTokenIsA400() {
        post("/api/v1/auth/verify-email", token("no-such-token"))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_token");
    }

    @Test
    void anEmailCanRegisterOnlyOnce() {
        String email = uniqueEmail();
        register(email).expectStatus().isCreated();
        register(email)
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("email_taken");
    }

    @Test
    void passwordResetChangesThePasswordOnce() {
        String email = verifiedCustomer();
        post("/api/v1/auth/forgot-password", "{\"email\":\"%s\"}".formatted(email)).expectStatus().isNoContent();
        ArgumentCaptor<String> captured = ArgumentCaptor.forClass(String.class);
        verify(mail).passwordReset(eq(email), any(), captured.capture());
        String token = captured.getValue();

        String reset = "{\"token\":\"%s\",\"password\":\"New-Password-42\"}".formatted(token);
        post("/api/v1/auth/reset-password", reset).expectStatus().isNoContent();

        post("/api/v1/auth/login", login(email, PASSWORD)).expectStatus().isUnauthorized();
        signIn(email, "New-Password-42");

        post("/api/v1/auth/reset-password", reset)
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_token");
    }

    @Test
    void aPasswordResetEndsEverySessionOfTheAccount() {
        String email = verifiedCustomer();
        String refreshToken = signIn(email, PASSWORD).get("refreshToken").asText();
        String refresh = "{\"refreshToken\":\"%s\"}".formatted(refreshToken);
        post("/api/v1/auth/refresh", refresh).expectStatus().isOk();

        post("/api/v1/auth/reset-password",
                "{\"token\":\"%s\",\"password\":\"Another-Pass-7\"}".formatted(resetTokenFor(email)))
                .expectStatus().isNoContent();

        post("/api/v1/auth/refresh", refresh).expectStatus().isUnauthorized();
    }

    @Test
    void aNewResetLinkCancelsTheEarlierOne() {
        String email = verifiedCustomer();
        String first = resetTokenFor(email);
        String second = resetTokenFor(email);

        post("/api/v1/auth/reset-password", "{\"token\":\"%s\",\"password\":\"Pass-Number-1\"}".formatted(first))
                .expectStatus().isBadRequest();
        post("/api/v1/auth/reset-password", "{\"token\":\"%s\",\"password\":\"Pass-Number-2\"}".formatted(second))
                .expectStatus().isNoContent();
    }

    @Test
    void aResetLinkUsedTwiceAtOnceWorksOnlyOnce() throws Exception {
        String email = verifiedCustomer();
        String body = "{\"token\":\"%s\",\"password\":\"Racing-Pass-5\"}".formatted(resetTokenFor(email));

        Callable<Integer> reset = () -> post("/api/v1/auth/reset-password", body)
                .returnResult(Void.class).getStatus().value();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : pool.invokeAll(List.of(reset, reset))) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(204, 400);
        }
    }

    @Test
    void tokensAreStoredOnlyAsHashes() {
        String email = uniqueEmail();
        register(email).expectStatus().isCreated();
        String verification = verificationTokenSentTo(email);
        post("/api/v1/auth/verify-email", token(verification)).expectStatus().isNoContent();
        String refreshToken = signIn(email, PASSWORD).get("refreshToken").asText();

        assertThat(count("SELECT count(*) AS n FROM auth_tokens WHERE purpose = 'email-verification' AND token_hash = :t", verification)).isZero();
        assertThat(count("SELECT count(*) AS n FROM auth_tokens WHERE purpose = 'email-verification' AND token_hash = :t",
                SecureTokens.hash(verification))).isOne();
        assertThat(count("SELECT count(*) AS n FROM auth_tokens WHERE purpose = 'refresh' AND token_hash = :t", refreshToken)).isZero();
        assertThat(count("SELECT count(*) AS n FROM auth_tokens WHERE purpose = 'refresh' AND token_hash = :t",
                SecureTokens.hash(refreshToken))).isOne();
    }

    @Test
    void forgotPasswordDoesNotRevealWhetherAnAccountExists() {
        post("/api/v1/auth/forgot-password", "{\"email\":\"nobody-%s@example.com\"}".formatted(UUID.randomUUID()))
                .expectStatus().isNoContent();
    }

    @Test
    void meReturnsTheSignedInCustomer() {
        http.get().uri("/api/v1/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken("customer@braseth.test"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.fullName").isEqualTo("Dev Customer");
    }

    private String verifiedCustomer() {
        String email = uniqueEmail();
        register(email).expectStatus().isCreated();
        post("/api/v1/auth/verify-email", token(verificationTokenSentTo(email))).expectStatus().isNoContent();
        return email;
    }

    /** Asks for a reset link and returns its token (the latest one sent to this address). */
    private String resetTokenFor(String email) {
        post("/api/v1/auth/forgot-password", "{\"email\":\"%s\"}".formatted(email)).expectStatus().isNoContent();
        ArgumentCaptor<String> captured = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce()).passwordReset(eq(email), any(), captured.capture());
        return captured.getValue();
    }

    private long count(String sql, String value) {
        return db.sql(sql).bind("t", value).map(row -> row.get("n", Long.class)).one().block();
    }

    private String verificationTokenSentTo(String email) {
        ArgumentCaptor<String> captured = ArgumentCaptor.forClass(String.class);
        verify(mail).verification(eq(email), any(), captured.capture());
        return captured.getValue();
    }

    private ResponseSpec register(String email) {
        String phone = "+85597" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000);
        return post("/api/v1/auth/register", """
                {"fullName":"Test Customer","email":"%s","phone":"%s","password":"%s",
                 "kind":"individual"}
                """.formatted(email, phone, PASSWORD));
    }

    private ResponseSpec post(String path, String json) {
        return http.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue(json).exchange();
    }

    private static String login(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    private static String token(String token) {
        return "{\"token\":\"%s\"}".formatted(token);
    }

    private static String uniqueEmail() {
        return "it-" + UUID.randomUUID() + "@example.com";
    }
}
