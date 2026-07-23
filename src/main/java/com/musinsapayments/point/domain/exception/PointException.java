package com.musinsapayments.point.domain.exception;

public class PointException extends RuntimeException {

    private final PointErrorCode errorCode;

    public PointException(PointErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public PointErrorCode getErrorCode() {
        return errorCode;
    }
}
