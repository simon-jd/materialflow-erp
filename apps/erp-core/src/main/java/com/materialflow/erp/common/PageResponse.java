package com.materialflow.erp.common;

import java.util.List;

/** One page of a stable, bounded list query. {@code page} is zero-based. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
    public static final int MAX_SIZE = 100;

    public PageResponse {
        items = List.copyOf(items);
    }

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
        int totalPages = (int) ((totalItems + size - 1) / size);
        return new PageResponse<>(items, page, size, totalItems, totalPages);
    }

    public static long offset(int page, int size) {
        return (long) page * size;
    }
}
