package com.braseth.ecommerce.infrastructure.mail;

import reactor.core.publisher.Mono;

public interface EmailService {

    Mono<Void> sendVerificationEmail(String email, String name, String token);

    Mono<Void> sendPasswordResetEmail(String email, String name, String token);

    Mono<Void> sendWelcomeEmail(String email, String name);

    Mono<Void> sendEmail(String to, String subject, String body, boolean html);
}