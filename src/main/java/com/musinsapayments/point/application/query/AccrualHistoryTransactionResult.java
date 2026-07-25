package com.musinsapayments.point.application.query;

public record AccrualHistoryTransactionResult(
        TransactionSummaryResult transaction,
        long allocatedAmount) {
}
