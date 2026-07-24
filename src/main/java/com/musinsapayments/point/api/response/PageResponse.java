package com.musinsapayments.point.api.response;

import com.musinsapayments.point.application.query.PageResult;
import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(
        List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <S, T> PageResponse<T> from(PageResult<S> result, Function<S, T> converter) {
        return new PageResponse<>(result.content().stream().map(converter).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }
}
