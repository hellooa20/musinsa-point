package com.musinsapayments.point.application.query;

import java.time.OffsetDateTime;

public record PointBalanceResult(long customerId, long balance, OffsetDateTime calculatedAt) {
}
