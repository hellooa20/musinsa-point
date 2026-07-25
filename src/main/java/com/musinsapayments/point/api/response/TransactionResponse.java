package com.musinsapayments.point.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.musinsapayments.point.application.query.AccrualHistoryTransactionResult;
import com.musinsapayments.point.application.query.TransactionDetailResult;
import com.musinsapayments.point.application.query.TransactionSummaryResult;
import com.musinsapayments.point.domain.ledger.AccrualTransactionType;
import com.musinsapayments.point.domain.ledger.PointType;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record TransactionResponse(
        String pointKey,
        long customerId,
        PointType pointType,
        AccrualTransactionType transactionType,
        String referencePointKey,
        String orderNumber,
        long amount,
        Long allocatedAmount,
        Long remainingAmount,
        Long balanceAfter,
        String status,
        OffsetDateTime expiresAt,
        OffsetDateTime occurredAt,
        String transactionDate,
        List<LedgerDetailResponse> details) {

    public record LedgerDetailResponse(
            String sourceAccrualPointKey,
            String targetAccrualPointKey,
            long amount,
            int sequenceNo) {
    }

    public static TransactionResponse fromSummary(TransactionSummaryResult result) {
        return new TransactionResponse(
                result.pointKey(), result.customerId(), result.pointType(), result.transactionType(),
                result.referencePointKey(), result.orderNumber(), result.amount(),
                null, result.remainingAmount(), result.balanceAfter(), result.status(), result.expiresAt(),
                result.occurredAt(), result.transactionDate().format(DateTimeFormatter.BASIC_ISO_DATE),
                List.of());
    }

    public static TransactionResponse fromHistory(AccrualHistoryTransactionResult result) {
        TransactionSummaryResult transaction = result.transaction();
        return new TransactionResponse(
                transaction.pointKey(), transaction.customerId(), transaction.pointType(),
                transaction.transactionType(), transaction.referencePointKey(), transaction.orderNumber(),
                transaction.amount(), result.allocatedAmount(), transaction.remainingAmount(),
                transaction.balanceAfter(), transaction.status(), transaction.expiresAt(),
                transaction.occurredAt(),
                transaction.transactionDate().format(DateTimeFormatter.BASIC_ISO_DATE),
                List.of());
    }

    public static TransactionResponse fromDetail(TransactionDetailResult result) {
        List<LedgerDetailResponse> details = result.details().stream()
                .map(detail -> new LedgerDetailResponse(
                        detail.sourceAccrualPointKey(), detail.targetAccrualPointKey(),
                        detail.amount(), detail.sequenceNo()))
                .toList();
        return new TransactionResponse(
                result.pointKey(), result.customerId(), result.pointType(), result.transactionType(),
                result.referencePointKey(), result.orderNumber(), result.amount(),
                null, result.remainingAmount(), result.balanceAfter(), result.status(), result.expiresAt(),
                result.occurredAt(), result.transactionDate().format(DateTimeFormatter.BASIC_ISO_DATE), details);
    }
}
