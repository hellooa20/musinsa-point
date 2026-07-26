package com.musinsapayments.point.repository;

/**
 * 원적립별 누적 사용 취소 금액 조회 결과
 */
public record CanceledAmountBySource(
        String sourceAccrualPointKey,
        long canceledAmount) {
}
