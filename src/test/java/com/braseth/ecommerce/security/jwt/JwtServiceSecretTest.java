package com.braseth.ecommerce.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JwtServiceSecretTest {

    @Test
    void rejectsAnUnresolvedPlaceholder() {
        assertThatThrownBy(() -> JwtService.signingKey("${JWT_SECRET}"))
                .isInstanceOf(InvalidJwtSecretException.class)
                .hasMessageContaining("JWT_SECRET environment variable is missing");
    }

    @Test
    void rejectsABlankSecret() {
        assertThatThrownBy(() -> JwtService.signingKey(" ")).isInstanceOf(InvalidJwtSecretException.class);
        assertThatThrownBy(() -> JwtService.signingKey(null)).isInstanceOf(InvalidJwtSecretException.class);
    }

    @Test
    void rejectsAShortSecret() {
        assertThatThrownBy(() -> JwtService.signingKey("too-short"))
                .isInstanceOf(InvalidJwtSecretException.class)
                .hasMessageContaining("9 bytes");
    }

    @Test
    void acceptsA256BitSecret() {
        assertThat(JwtService.signingKey("0123456789abcdef0123456789abcdef")).isNotNull();
    }
}
