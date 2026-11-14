package com.migrateflow.common;

/**
 * Shared pagination bounds for list endpoints. Page and size are clamped
 * to sane ranges and the offset is computed in long arithmetic, so a
 * hostile or buggy client cannot overflow int offsets or force unbounded
 * scans (Showcase review S-21).
 */
public final class PageRequestUtil {
    private PageRequestUtil() {}

    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_PAGE = 10_000;

    public static int clampPage(int page) {
        return Math.min(MAX_PAGE, Math.max(0, page));
    }

    public static int clampSize(int size) {
        return Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    }

    /** Alias for {@link #clampPage(int)}. */
    public static int validatePage(int page) {
        return clampPage(page);
    }

    /** Alias for {@link #clampSize(int)}. */
    public static int validateSize(int size) {
        return clampSize(size);
    }

    public static long safeOffset(int page, int size) {
        return (long) clampPage(page) * clampSize(size);
    }
}
