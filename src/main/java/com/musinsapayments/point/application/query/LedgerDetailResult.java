package com.musinsapayments.point.application.query;

public record LedgerDetailResult(
        String sourceAccrualPointKey,
        String targetAccrualPointKey,
        long amount,
        int sequenceNo) {
}
