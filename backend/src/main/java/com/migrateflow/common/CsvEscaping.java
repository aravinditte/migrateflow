package com.migrateflow.common;

/** Spreadsheet formula-injection escaping for exported CSV cells (spec 12). */
public final class CsvEscaping {
    private CsvEscaping() {}

    public static String escapeCell(String value) {
        if (value == null) return "";
        String v = value;
        if (!v.isEmpty()) {
            char c = v.charAt(0);
            if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') {
                v = "'" + v;
            }
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            v = '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
