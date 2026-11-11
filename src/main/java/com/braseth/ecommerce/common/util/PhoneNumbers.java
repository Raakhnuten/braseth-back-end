package com.braseth.ecommerce.common.util;

import java.util.regex.Pattern;

/**
 * Puts phone numbers into one stored form, +855XXXXXXXX, so the same number
 * typed as "012 345 678", "85512345678" or "+855 12-345-678" is one account.
 * Numbers already in international form (+XX…) are kept as typed.
 */
public final class PhoneNumbers {

    private static final Pattern INTERNATIONAL = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

    private PhoneNumbers() {
    }

    /** The normalised number, or null when it cannot be a phone number. */
    public static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[\\s\\-().]", "");
        if (digits.startsWith("00")) {
            digits = "+" + digits.substring(2);
        } else if (digits.startsWith("0")) {
            digits = "+855" + digits.substring(1);
        } else if (digits.startsWith("855")) {
            digits = "+" + digits;
        }
        return INTERNATIONAL.matcher(digits).matches() ? digits : null;
    }
}
