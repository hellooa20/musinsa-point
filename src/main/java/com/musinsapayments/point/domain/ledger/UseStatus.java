package com.musinsapayments.point.domain.ledger;

/**
 * 포인트 사용 상태
 */
public enum UseStatus {
    USED,   // 사용
    PARTIALLY_CANCELED, // 부분 취소
    FULLY_CANCELED  // 전체 취소
}
