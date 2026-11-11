package com.braseth.ecommerce.infrastructure.recaptcha;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.config.BrasethProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Checks a Google reCAPTCHA token with Google before sign-in or registration.
 *
 * The token the browser sends proves nothing by itself; only Google's answer,
 * asked with the secret key, does. Each token can be checked once. With no
 * secret configured the check is off and every request passes.
 *
 * Fails closed: if Google cannot be reached the request is refused, since a
 * check that passes whenever it errors would be trivially bypassed.
 */
@Slf4j
@Service
public class RecaptchaVerifier {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    static final String REQUIRED = "Please confirm you are not a robot.";
    static final String FAILED = "The security check did not pass. Please try again.";
    static final String UNAVAILABLE = "We could not run the security check just now. Please try again.";

    private final BrasethProperties.Recaptcha settings;
    private final WebClient client;

    @Autowired
    RecaptchaVerifier(BrasethProperties properties, WebClient.Builder builder) {
        this(properties.recaptcha(), builder);
    }

    RecaptchaVerifier(BrasethProperties.Recaptcha settings, WebClient.Builder builder) {
        this.settings = settings;
        this.client = builder.build();
        if (!settings.enabled()) {
            log.info("reCAPTCHA is off: set RECAPTCHA_SECRET to require it on customer sign-in and registration");
        }
    }

    /** Completes empty when the token is good (or the check is off); errors otherwise. */
    public Mono<Void> verify(String token) {
        if (!settings.enabled()) {
            return Mono.empty();
        }
        if (token == null || token.isBlank()) {
            return Mono.error(ApiProblem.badRequest("recaptcha_required", REQUIRED));
        }
        return client.post()
                .uri(settings.verifyUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("secret", settings.secret().strip())
                        .with("response", token.strip()))
                .retrieve()
                .bodyToMono(SiteVerifyResponse.class)
                .timeout(TIMEOUT)
                .onErrorMap(error -> !(error instanceof ApiProblem), error -> {
                    log.warn("reCAPTCHA check could not run: {}", error.getClass().getSimpleName());
                    return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "recaptcha_unavailable", UNAVAILABLE);
                })
                .flatMap(response -> {
                    if (response.success()) {
                        return Mono.<Void>empty();
                    }
                    log.warn("reCAPTCHA rejected: {}", response.errorCodes());
                    return Mono.error(ApiProblem.badRequest("recaptcha_failed", FAILED));
                });
    }

    /** The part of Google's answer this check needs. */
    record SiteVerifyResponse(boolean success, @JsonProperty("error-codes") List<String> errorCodes) {
    }
}
