package com.braseth.ecommerce.common.util;

import java.util.List;
import java.util.stream.Collectors;

/**
 * CSV for staff exports. Every cell is quoted, and a cell starting with a
 * formula character gets a leading apostrophe, so a spreadsheet shows
 * "=HYPERLINK(...)" typed into a form as text instead of running it.
 */
public final class Csv {

    private Csv() {
    }

    public static String of(List<List<Object>> rows) {
        return rows.stream()
                .map(row -> row.stream().map(Csv::cell).collect(Collectors.joining(",")))
                .collect(Collectors.joining("\r\n"));
    }

    static String cell(Object value) {
        String text = value == null ? "" : value.toString();
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
