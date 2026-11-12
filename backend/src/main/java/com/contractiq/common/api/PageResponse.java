package com.contractiq.common.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * A stable JSON shape for paginated lists, so the API does not depend on Spring's internal Page format.
 *
 * @param items         the items on this page
 * @param page          zero-based page number
 * @param size          page size that was applied (after capping)
 * @param totalElements total number of items across all pages
 * @param totalPages    total number of pages
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
