package com.braseth.ecommerce.modules.auth.service;

import org.springframework.stereotype.Component;

import com.braseth.ecommerce.config.AppProperties;
import com.braseth.ecommerce.config.BrasethProperties;
import com.braseth.ecommerce.infrastructure.mail.EmailService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Sends the account e-mails through {@link EmailService}.
 *
 * With {@code braseth.v1.mail.log-only=true} (the local profile) nothing is
 * sent: the link is written to the log instead, so trying sign-up locally
 * never mails a real or made-up address from the shop's account.
 *
 * A send that fails is logged and swallowed. The account change it follows
 * has already happened, and the customer can ask for the e-mail again
 * (resend-verification, or forgot-password again).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AccountMail {

    private final EmailService emailService;
    private final AppProperties appProperties;
    private final BrasethProperties properties;

    Mono<Void> verification(String email, String name, String token) {
        if (properties.mail().logOnly()) {
            log.info("[log-only mail] Verify {}: {}/verify-email?token={}", email, appProperties.getFrontendUrl(), token);
            return Mono.empty();
        }
        return quietly(emailService.sendVerificationEmail(email, name, token), email);
    }

    Mono<Void> passwordReset(String email, String name, String token) {
        if (properties.mail().logOnly()) {
            log.info("[log-only mail] Reset {}: {}/reset-password?token={}", email, appProperties.getFrontendUrl(), token);
            return Mono.empty();
        }
        return quietly(emailService.sendPasswordResetEmail(email, name, token), email);
    }

    /**
     * A staff invitation. It is the password-reset e-mail with its link: the
     * one step the new member of staff takes is choosing a password.
     */
    Mono<Void> invitation(String email, String name, String token) {
        if (properties.mail().logOnly()) {
            log.info("[log-only mail] Staff invitation {}: {}/reset-password?token={}", email,
                    appProperties.getFrontendUrl(), token);
            return Mono.empty();
        }
        return quietly(emailService.sendPasswordResetEmail(email, name, token), email);
    }

    private Mono<Void> quietly(Mono<Void> send, String email) {
        // JavaMailSender blocks; keep it off the event loop.
        return Mono.defer(() -> send)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    log.error("Could not send account e-mail to {}: {}", email, error.getMessage());
                    return Mono.empty();
                });
    }
}
