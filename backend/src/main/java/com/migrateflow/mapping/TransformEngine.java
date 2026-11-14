package com.migrateflow.mapping;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Set;

/**
 * Deterministic allow-listed transformations (spec 4.3). No expressions, no
 * scripting. A step that cannot be applied yields a stable error code and a
 * null value; steps never guess.
 */
public final class TransformEngine {
    private TransformEngine() {}

    public record Result(String value, String errorCode) {
        public boolean ok() { return errorCode == null; }
        static Result ok(String v) { return new Result(v, null); }
        static Result error(String code) { return new Result(null, code); }
    }

    public static final Set<String> OPERATIONS = Set.of(
            "TRIM", "UPPERCASE", "LOWERCASE", "NULL_IF_BLANK",
            "PARSE_DATE", "PARSE_LONG", "ENUM_MAP", "DEFAULT_VALUE");

    public static final Set<String> ORDER_STATUSES = Set.of("NEW", "PAID", "SHIPPED", "CANCELLED");

    /** Validates step arguments at mapping-publish time. Returns an error message or null. */
    public static String validateStep(MappingDefinition.TransformStep step) {
        if (step == null) return "Transform step cannot be null";
        if (step.operation() == null || !OPERATIONS.contains(step.operation())) {
            return "Unknown transformation: " + (step.operation() == null ? "null" : step.operation());
        }
        if (step.args() == null && (step.operation().equals("PARSE_DATE") || step.operation().equals("ENUM_MAP") || step.operation().equals("DEFAULT_VALUE"))) {
            return step.operation() + " requires arguments";
        }
        switch (step.operation()) {
            case "PARSE_DATE" -> {
                String pattern = step.args().get("pattern");
                if (pattern == null || pattern.isBlank()) return "PARSE_DATE requires a pattern argument";
                try {
                    DateTimeFormatter.ofPattern(pattern.replace("yyyy", "uuuu")).withResolverStyle(ResolverStyle.STRICT);
                } catch (IllegalArgumentException e) {
                    return "Invalid date pattern: " + pattern;
                }
            }
            case "ENUM_MAP" -> {
                if (step.args().isEmpty()) return "ENUM_MAP requires at least one value mapping";
            }
            case "DEFAULT_VALUE" -> {
                if (!step.args().containsKey("value")) return "DEFAULT_VALUE requires a value argument";
            }
            default -> { }
        }
        return null;
    }

    public static Result apply(List<MappingDefinition.TransformStep> steps, String raw) {
        if (steps == null) return Result.ok(raw);
        String value = raw;
        for (MappingDefinition.TransformStep step : steps) {
            if (step == null || step.operation() == null) continue;
            if (value == null && !"DEFAULT_VALUE".equals(step.operation())) continue;
            switch (step.operation()) {
                case "TRIM" -> value = value.trim();
                case "UPPERCASE" -> value = value.toUpperCase(java.util.Locale.ROOT);
                case "LOWERCASE" -> value = value.toLowerCase(java.util.Locale.ROOT);
                case "NULL_IF_BLANK" -> value = value.isBlank() ? null : value;
                case "DEFAULT_VALUE" -> {
                    if (value == null || value.isBlank()) value = step.args().get("value");
                }
                case "PARSE_DATE" -> {
                    String pattern = step.args().get("pattern");
                    DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern.replace("yyyy", "uuuu"))
                            .withResolverStyle(ResolverStyle.STRICT);
                    try {
                        value = LocalDate.parse(value, fmt).format(DateTimeFormatter.ISO_LOCAL_DATE);
                    } catch (DateTimeParseException e) {
                        return Result.error("DATE_FORMAT");
                    }
                }
                case "PARSE_LONG" -> {
                    try {
                        value = Long.toString(Long.parseLong(value));
                    } catch (NumberFormatException e) {
                        return Result.error("INTEGER_FORMAT");
                    }
                }
                case "ENUM_MAP" -> {
                    String mapped = step.args().get(value);
                    if (mapped == null) return Result.error("INVALID_STATUS");
                    value = mapped;
                }
                default -> {
                    return Result.error("INVALID_TRANSFORM");
                }
            }
        }
        return Result.ok(value);
    }
}
