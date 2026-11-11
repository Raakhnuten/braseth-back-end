package com.braseth.ecommerce.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SecureTokensTest {

    @Test
    void tokensAreUrlSafeAndCarry256Bits() {
        String token = SecureTokens.generate();
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void tokensDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            assertThat(seen.add(SecureTokens.generate())).isTrue();
        }
    }

    @Test
    void hashIsStableSha256Hex() {
        assertThat(SecureTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(SecureTokens.hash("abc")).isEqualTo(SecureTokens.hash("abc"));
    }
}
