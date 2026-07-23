package com.musinsapayments.point.domain.ledger;

/**
 * 포인트 원장 타입
 */
public enum PointType {
    ACCRUAL,    // 적립
    ACCRUAL_CANCEL, // 적립 취소
    USE,    // 사용
    USE_CANCEL  // 사용 취소
}
