package com.musinsapayments.point.application.query;

import java.util.List;
import org.springframework.data.domain.Page;

public record PageResult<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public PageResult {
        content = List.copyOf(content);
    }

    public static <T> PageResult<T> from(Page<?> page, List<T> content) {
        return new PageResult<>(content, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
