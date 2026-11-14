package com.migrateflow.common;

/** Spreadsheet formula-injection escaping for exported CSV cells (spec 12). */
public final class CsvEscaping {
    private CsvEscaping() {}

    public static String escapeCell(String value) {
        if (value == null) return "";
        String v = value;
        if (!v.isEmpty()) {
            String stripped = v.stripLeading();
            if (!stripped.isEmpty()) {
                char c = stripped.charAt(0);
                if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') {
                    v = "'" + v;
                }
            } else {
                char c = v.charAt(0);
                if (c == '\t' || c == '\r') {
                    v = "'" + v;
                }
            }
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
