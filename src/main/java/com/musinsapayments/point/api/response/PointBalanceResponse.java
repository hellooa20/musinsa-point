package com.musinsapayments.point.api.response;

import com.musinsapayments.point.application.query.PointBalanceResult;
import java.time.OffsetDateTime;

public record PointBalanceResponse(long customerId, long balance, OffsetDateTime calculatedAt) {

    public static PointBalanceResponse from(PointBalanceResult result) {
        return new PointBalanceResponse(result.customerId(), result.balance(), result.calculatedAt());
    }
}
