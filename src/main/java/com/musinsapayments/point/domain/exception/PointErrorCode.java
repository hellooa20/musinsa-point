package com.musinsapayments.point.domain.exception;

public enum PointErrorCode {
    INVALID_REQUEST("요청값을 확인해 주세요."),
    POLICY_NOT_FOUND("고객 포인트 정책을 찾을 수 없습니다."),
    POINT_NOT_FOUND("포인트 거래를 찾을 수 없습니다."),
    REQUEST_ID_CONFLICT("requestId가 다른 요청에 이미 사용되었습니다."),
    ORDER_NUMBER_CONFLICT("주문번호가 이미 사용되었습니다."),
    ACCRUAL_AMOUNT_LIMIT_EXCEEDED("1회 적립 가능 금액을 초과했습니다."),
    HOLDING_LIMIT_EXCEEDED("고객 보유 한도를 초과했습니다."),
    POINT_BALANCE_INSUFFICIENT("사용 가능한 포인트가 부족합니다."),
    ACCRUAL_CANCEL_NOT_ALLOWED("적립을 취소할 수 없습니다."),
    USE_CANCEL_AMOUNT_EXCEEDED("사용취소 가능 금액을 초과했습니다."),
    LOCK_TIMEOUT("다른 포인트 요청을 처리 중입니다. 같은 요청으로 다시 시도해 주세요."),
    DATA_INTEGRITY_VIOLATION("데이터 정합성 제약을 위반했습니다."),
    INTERNAL_ERROR("서버 내부 오류가 발생했습니다.");

    private final String message;

    PointErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
