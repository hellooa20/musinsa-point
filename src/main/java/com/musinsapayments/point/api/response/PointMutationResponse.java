package com.musinsapayments.point.api.response;

import com.musinsapayments.point.application.PointMutationResult;
import com.musinsapayments.point.domain.ledger.PointType;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

public record PointMutationResponse(
        String pointKey,
        long customerId,
        PointType pointType,
        String referencePointKey,
        String orderNumber,
        long amount,
        long balanceAfter,
        OffsetDateTime occurredAt,
        String transactionDate,
        OffsetDateTime expiresAt) {

    public static PointMutationResponse from(PointMutationResult result) {
        return new PointMutationResponse(
                result.pointKey(), result.customerId(), result.pointType(),
                result.referencePointKey(), result.orderNumber(), result.amount(),
                result.balanceAfter(), result.occurredAt(),
                result.transactionDate().format(DateTimeFormatter.BASIC_ISO_DATE), result.expiresAt());
    }
}
