package com.musinsapayments.point.application.query;

import com.musinsapayments.point.domain.ledger.AccrualTransactionType;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.ledger.PointType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record TransactionDetailResult(
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
        LocalDate transactionDate,
        List<LedgerDetailResult> details) {

    public TransactionDetailResult {
        details = List.copyOf(details);
    }

    public static TransactionDetailResult from(
            PointLedger ledger, String status, List<LedgerDetailResult> details) {
        return new TransactionDetailResult(
                ledger.getPointKey(), ledger.getCustomerId(), ledger.getPointType(),
                ledger.getTransactionType(), ledger.getReferencePointKey(), ledger.getOrderNumber(),
                ledger.getAmount(), ledger.getRemainingAmount(), ledger.getBalanceAfter(), status,
                ledger.getExpiresAt(), ledger.getOccurredAt(), ledger.getTransactionDate(), details);
    }
}
