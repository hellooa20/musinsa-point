package com.musinsapayments.point.application;

import com.musinsapayments.point.domain.ledger.AccrualTransactionType;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.ledger.PointType;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record PointMutationResult(
        String pointKey,
        long customerId,
        PointType pointType,
        String referencePointKey,
        String orderNumber,
        long amount,
        long balanceAfter,
        OffsetDateTime occurredAt,
        LocalDate transactionDate,
        OffsetDateTime expiresAt) {

    public static PointMutationResult from(PointLedger ledger) {
        if (ledger.getTransactionType() == AccrualTransactionType.EXPIRED_USE_REFUND
                || ledger.getBalanceAfter() == null) {
            throw new IllegalArgumentException("내부 재적립은 변경 응답으로 반환하지 않습니다.");
        }
        return new PointMutationResult(
                ledger.getPointKey(), ledger.getCustomerId(), ledger.getPointType(),
                ledger.getReferencePointKey(), ledger.getOrderNumber(), ledger.getAmount(),
                ledger.getBalanceAfter(), ledger.getOccurredAt(), ledger.getTransactionDate(),
                ledger.getExpiresAt());
    }
}
