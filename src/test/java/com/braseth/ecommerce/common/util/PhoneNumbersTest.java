package com.braseth.ecommerce.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumbersTest {

    @ParameterizedTest
    @ValueSource(strings = { "012 345 678", "012-345-678", "85512345678", "+855 12-345-678", "(012) 345.678" })
    void normalisesCambodianNumbersToOneForm(String typed) {
        assertThat(PhoneNumbers.normalise(typed)).isEqualTo("+85512345678");
    }

    @Test
    void keepsOtherInternationalNumbers() {
        assertThat(PhoneNumbers.normalise("+1 202 555 0123")).isEqualTo("+12025550123");
        assertThat(PhoneNumbers.normalise("0012025550123")).isEqualTo("+12025550123");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "abc", "12", "+0123456789" })
    void rejectsWhatCannotBeAPhoneNumber(String typed) {
        assertThat(PhoneNumbers.normalise(typed)).isNull();
    }

    @Test
    void nullStaysNull() {
        assertThat(PhoneNumbers.normalise(null)).isNull();
    }
}
