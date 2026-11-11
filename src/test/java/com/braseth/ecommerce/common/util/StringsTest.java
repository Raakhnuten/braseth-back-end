package com.braseth.ecommerce.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StringsTest {

    @Test
    void blankToNullTrimsTextAndDropsBlanks() {
        assertThat(Strings.blankToNull("  shirts ")).isEqualTo("shirts");
        assertThat(Strings.blankToNull("   ")).isNull();
        assertThat(Strings.blankToNull("")).isNull();
        assertThat(Strings.blankToNull(null)).isNull();
    }
}
