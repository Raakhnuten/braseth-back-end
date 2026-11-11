package com.braseth.ecommerce.security.jwt;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a bad {@code jwt.secret} into Spring Boot's short "APPLICATION FAILED
 * TO START" report instead of a page of bean-creation stack trace.
 */
public class JwtSecretFailureAnalyzer extends AbstractFailureAnalyzer<InvalidJwtSecretException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidJwtSecretException cause) {
        return new FailureAnalysis(cause.getMessage(),
                "Set the JWT_SECRET environment variable to a random value of at least 32 characters "
                        + "(generate one with: openssl rand -hex 32), or run with the 'local' profile "
                        + "(-Dspring-boot.run.profiles=local), which has its own development secret.",
                cause);
    }
}
