package com.braseth.ecommerce.security.jwt;

/**
 * The {@code jwt.secret} setting is missing or too short to sign tokens with.
 * Reported at startup by {@link JwtSecretFailureAnalyzer}.
 */
public class InvalidJwtSecretException extends IllegalStateException {

    public InvalidJwtSecretException(String message) {
        super(message);
    }
}
