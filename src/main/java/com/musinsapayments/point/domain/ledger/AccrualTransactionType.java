package com.musinsapayments.point.domain.ledger;

/**
 * 적립 포인트 원장 거래 타입
 */
public enum AccrualTransactionType {
    NORMAL, // 일반 적립
    MANUAL, // 수기 적립 (관리자)
    EXPIRED_USE_REFUND  // 취소건 만료 재적립
}
