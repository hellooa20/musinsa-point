package com.musinsapayments.point.application.query;

import java.util.List;

public record AccrualHistoryResult(String accrualPointKey, List<TransactionSummaryResult> transactions) {

    public AccrualHistoryResult {
        transactions = List.copyOf(transactions);
    }
}
