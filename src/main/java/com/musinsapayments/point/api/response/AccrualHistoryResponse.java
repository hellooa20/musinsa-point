package com.musinsapayments.point.api.response;

import com.musinsapayments.point.application.query.AccrualHistoryResult;
import java.util.List;

public record AccrualHistoryResponse(String accrualPointKey, List<TransactionResponse> transactions) {

    public static AccrualHistoryResponse from(AccrualHistoryResult result) {
        return new AccrualHistoryResponse(result.accrualPointKey(), result.transactions().stream()
                .map(TransactionResponse::fromSummary)
                .toList());
    }
}
