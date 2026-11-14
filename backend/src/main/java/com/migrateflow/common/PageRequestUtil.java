package com.migrateflow.common;

/**
 * Bounds checking and safe offset calculation for pagination (spec 10.5 / MF-026).
 */
public final class PageRequestUtil {
    private PageRequestUtil() {}

    public static int validatePage(int page) {
        if (page < 0) {
            throw ApiException.badRequest("INVALID_PAGE", "Page index must be >= 0");
        }
        return page;
    }

    public static int validateSize(int size) {
        if (size < 1 || size > 100) {
            throw ApiException.badRequest("INVALID_PAGE_SIZE", "Page size must be between 1 and 100");
        }
        return size;
    }

    public static int safeOffset(int page, int size) {
        validatePage(page);
        validateSize(size);
        long offset = (long) page * (long) size;
        if (offset > 1_000_000L) {
            throw ApiException.badRequest("OFFSET_TOO_LARGE", "Offset exceeds maximum limit");
        }
        return (int) offset;
    }
}
