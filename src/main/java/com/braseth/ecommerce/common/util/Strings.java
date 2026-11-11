package com.braseth.ecommerce.common.util;

/** Small string helpers for request input. */
public final class Strings {

    private Strings() {
    }

    /** The trimmed value, or null when it is null or only whitespace. */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
