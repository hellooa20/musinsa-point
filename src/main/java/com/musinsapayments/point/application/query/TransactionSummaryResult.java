package com.musinsapayments.point.application.query;

import com.musinsapayments.point.domain.ledger.AccrualTransactionType;
import com.musinsapayments.point.domain.ledger.PointType;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record TransactionSummaryResult(
        String pointKey,
        long customerId,
        PointType pointType,
        AccrualTransactionType transactionType,
        String referencePointKey,
        String orderNumber,
        long amount,
        Long remainingAmount,
        Long balanceAfter,
        String status,
        OffsetDateTime expiresAt,
        OffsetDateTime occurredAt,
        LocalDate transactionDate) {
}
