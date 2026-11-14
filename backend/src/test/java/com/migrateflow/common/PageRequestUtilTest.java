package com.migrateflow.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PageRequestUtilTest {

    @Test
    void clampPageHandlesBoundaries() {
        assertEquals(0, PageRequestUtil.clampPage(-1));
        assertEquals(0, PageRequestUtil.clampPage(-999));
        assertEquals(0, PageRequestUtil.clampPage(0));
        assertEquals(1, PageRequestUtil.clampPage(1));
        assertEquals(10_000, PageRequestUtil.clampPage(10_000));
        assertEquals(10_000, PageRequestUtil.clampPage(10_001));
        assertEquals(10_000, PageRequestUtil.clampPage(Integer.MAX_VALUE));
    }

    @Test
    void clampSizeHandlesBoundaries() {
        assertEquals(1, PageRequestUtil.clampSize(0));
        assertEquals(1, PageRequestUtil.clampSize(-1));
        assertEquals(1, PageRequestUtil.clampSize(-50));
        assertEquals(1, PageRequestUtil.clampSize(1));
        assertEquals(25, PageRequestUtil.clampSize(25));
        assertEquals(50, PageRequestUtil.clampSize(50));
        assertEquals(50, PageRequestUtil.clampSize(51));
        assertEquals(50, PageRequestUtil.clampSize(100));
        assertEquals(50, PageRequestUtil.clampSize(Integer.MAX_VALUE));
    }

    @Test
    void validateAliasesMatchClampMethods() {
        assertEquals(PageRequestUtil.clampPage(-5), PageRequestUtil.validatePage(-5));
        assertEquals(PageRequestUtil.clampPage(42), PageRequestUtil.validatePage(42));
        assertEquals(PageRequestUtil.clampPage(99_999), PageRequestUtil.validatePage(99_999));

        assertEquals(PageRequestUtil.clampSize(0), PageRequestUtil.validateSize(0));
        assertEquals(PageRequestUtil.clampSize(30), PageRequestUtil.validateSize(30));
        assertEquals(PageRequestUtil.clampSize(100), PageRequestUtil.validateSize(100));
    }

    @Test
    void safeOffsetUsesLongArithmetic() {
        assertEquals(0L, PageRequestUtil.safeOffset(0, 50));
        assertEquals(50L, PageRequestUtil.safeOffset(1, 50));
        assertEquals(100L, PageRequestUtil.safeOffset(2, 50));
        assertEquals(500_000L, PageRequestUtil.safeOffset(10_000, 50));
        assertEquals(0L, PageRequestUtil.safeOffset(-1, -1));
        assertEquals(500_000L, PageRequestUtil.safeOffset(99_999, 100));
    }
}
