package com.braseth.ecommerce.infrastructure.recaptcha;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.config.BrasethProperties;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RecaptchaVerifierTest {

    private final List<ClientRequest> requests = new ArrayList<>();

    private RecaptchaVerifier verifier(String secret, HttpStatus status, String body) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(ClientResponse.create(status)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        });
        return new RecaptchaVerifier(new BrasethProperties.Recaptcha(secret, "https://recaptcha.test/siteverify"),
                builder);
    }

    @Test
    void passesWhenGoogleSaysSuccess() {
        StepVerifier.create(verifier("secret", HttpStatus.OK, "{\"success\":true}").verify("token"))
                .verifyComplete();

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).method().name()).isEqualTo("POST");
        assertThat(requests.get(0).url().toString()).isEqualTo("https://recaptcha.test/siteverify");
    }

    @Test
    void refusesWhenGoogleSaysNo() {
        StepVerifier.create(verifier("secret", HttpStatus.OK,
                "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}").verify("token"))
                .expectErrorSatisfies(error -> assertProblem(error, HttpStatus.BAD_REQUEST, "recaptcha_failed"))
                .verify();
    }

    @Test
    void refusesAMissingTokenWithoutAskingGoogle() {
        StepVerifier.create(verifier("secret", HttpStatus.OK, "{\"success\":true}").verify(" "))
                .expectErrorSatisfies(error -> assertProblem(error, HttpStatus.BAD_REQUEST, "recaptcha_required"))
                .verify();

        assertThat(requests).isEmpty();
    }

    @Test
    void failsClosedWhenGoogleCannotBeReached() {
        StepVerifier.create(verifier("secret", HttpStatus.INTERNAL_SERVER_ERROR, "oops").verify("token"))
                .expectErrorSatisfies(
                        error -> assertProblem(error, HttpStatus.SERVICE_UNAVAILABLE, "recaptcha_unavailable"))
                .verify();
    }

    @Test
    void isOffWithoutASecret() {
        StepVerifier.create(verifier("", HttpStatus.OK, "{\"success\":false}").verify(null)).verifyComplete();

        assertThat(requests).isEmpty();
    }

    private static void assertProblem(Throwable error, HttpStatus status, String code) {
        assertThat(error).isInstanceOf(ApiProblem.class);
        ApiProblem problem = (ApiProblem) error;
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getCode()).isEqualTo(code);
    }
}
