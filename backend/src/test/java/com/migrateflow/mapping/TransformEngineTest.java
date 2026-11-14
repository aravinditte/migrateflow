package com.migrateflow.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TransformEngineTest {

    @Test
    void trimUppercaseChainIsDeterministic() {
        var r = TransformEngine.apply(List.of(
                MappingDefinition.TransformStep.of("TRIM"),
                MappingDefinition.TransformStep.of("UPPERCASE")), " c0020 ");
        assertTrue(r.ok());
        assertEquals("C0020", r.value());
    }

    @Test
    void nullIfBlankProducesNull() {
        var r = TransformEngine.apply(List.of(
                MappingDefinition.TransformStep.of("TRIM"),
                MappingDefinition.TransformStep.of("NULL_IF_BLANK")), "   ");
        assertTrue(r.ok());
        assertNull(r.value());
    }

    @Test
    void parseDateRejectsImpossibleDates() {
        var step = new MappingDefinition.TransformStep("PARSE_DATE", Map.of("pattern", "yyyy/MM/dd"));
        assertEquals("2025-01-12", TransformEngine.apply(List.of(step), "2025/01/12").value());
        assertEquals("DATE_FORMAT", TransformEngine.apply(List.of(step), "2025/02/30").errorCode());
        assertEquals("DATE_FORMAT", TransformEngine.apply(List.of(step), "2025/13/01").errorCode());
        assertEquals("DATE_FORMAT", TransformEngine.apply(List.of(step), "12/01/2025").errorCode());
    }

    @Test
    void parseLongRejectsNonIntegersAndOverflow() {
        var step = MappingDefinition.TransformStep.of("PARSE_LONG");
        assertEquals("12000", TransformEngine.apply(List.of(step), "12000").value());
        assertEquals("-500", TransformEngine.apply(List.of(step), "-500").value());
        assertEquals("INTEGER_FORMAT", TransformEngine.apply(List.of(step), "12.5").errorCode());
        assertEquals("INTEGER_FORMAT", TransformEngine.apply(List.of(step), "99999999999999999999").errorCode());
    }

    @Test
    void enumMapRejectsUnknownWithoutGuessing() {
        var step = new MappingDefinition.TransformStep("ENUM_MAP", Map.of("N", "NEW", "P", "PAID"));
        assertEquals("NEW", TransformEngine.apply(List.of(step), "N").value());
        assertEquals("INVALID_STATUS", TransformEngine.apply(List.of(step), "X").errorCode());
    }

    @Test
    void defaultValueFillsBlank() {
        var steps = List.of(
                MappingDefinition.TransformStep.of("NULL_IF_BLANK"),
                new MappingDefinition.TransformStep("DEFAULT_VALUE", Map.of("value", "NEW")));
        assertEquals("NEW", TransformEngine.apply(steps, "").value());
    }

    @Test
    void validateStepRejectsUnknownOperations() {
        assertNotNull(TransformEngine.validateStep(MappingDefinition.TransformStep.of("EXEC_SQL")));
        assertNull(TransformEngine.validateStep(MappingDefinition.TransformStep.of("TRIM")));
    }
}
