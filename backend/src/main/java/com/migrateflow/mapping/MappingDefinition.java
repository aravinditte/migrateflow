package com.migrateflow.mapping;

import java.util.List;
import java.util.Map;

/** Immutable mapping definition for the two known target entity types (spec 4.3). */
public record MappingDefinition(List<FieldMapping> customers, List<FieldMapping> orders) {

    public record FieldMapping(String sourceColumn, String targetField,
                               List<TransformStep> steps, boolean required) {}

    /** One allow-listed transformation step. Args: PARSE_DATE{pattern},
     *  DEFAULT_VALUE{value}, ENUM_MAP{values as k->v entries}. */
    public record TransformStep(String operation, Map<String, String> args) {
        public TransformStep {
            args = args == null ? Map.of() : Map.copyOf(args);
        }
        public static TransformStep of(String op) { return new TransformStep(op, Map.of()); }
    }

    public List<FieldMapping> forDataset(String dataset) {
        return "CUSTOMERS".equals(dataset) ? customers : orders;
    }
}
