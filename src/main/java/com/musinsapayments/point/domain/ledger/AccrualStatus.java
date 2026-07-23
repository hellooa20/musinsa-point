package com.musinsapayments.point.domain.ledger;

/**
 * 적립 포인트 원장 상태
 */
public enum AccrualStatus {
    CANCELED,   // 적립 취소
    EXPIRED,    // 만료
    EXHAUSTED,  // 전체 사용
    PARTIALLY_AVAILABLE,    // 부분 사용 가능
    AVAILABLE   // 전체 사용 가능
}
