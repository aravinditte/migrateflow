package com.migrateflow.common;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommonTest {

    @Test
    void canonicalHashIsOrderIndependentAndStable() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", "2");
        a.put("a", "1");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", "1");
        b.put("b", "2");
        assertEquals(CanonicalHash.hashFields(a), CanonicalHash.hashFields(b));
        assertEquals(64, CanonicalHash.hashFields(a).length());
    }

    @Test
    void canonicalHashDistinguishesNullFromEmpty() {
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("x", null);
        Map<String, Object> withEmpty = new LinkedHashMap<>();
        withEmpty.put("x", "");
        assertNotEquals(CanonicalHash.hashFields(withNull), CanonicalHash.hashFields(withEmpty));
    }

    @Test
    void csvEscapingNeutralisesFormulaCells() {
        assertEquals("'=SUM(A1:A2)", CsvEscaping.escapeCell("=SUM(A1:A2)"));
        assertEquals("'+cmd", CsvEscaping.escapeCell("+cmd"));
        assertEquals("plain", CsvEscaping.escapeCell("plain"));
        assertEquals("\"a,b\"", CsvEscaping.escapeCell("a,b"));
        assertEquals("' -2+3*[1]", CsvEscaping.escapeCell(" -2+3*[1]"));
        assertEquals("'\t=cmd", CsvEscaping.escapeCell("\t=cmd"));
        assertEquals("'@SUM", CsvEscaping.escapeCell("@SUM"));
    }
}
