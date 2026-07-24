package com.musinsapayments.point.api.error;

import java.time.OffsetDateTime;
import java.util.List;

public record PointErrorResponse(
        OffsetDateTime timestamp,
        String code,
        String message,
        List<FieldErrorItem> fieldErrors) {

    public record FieldErrorItem(String field, String message) {
    }
}
