package com.musinsapayments.point.domain.use;

public record PointCancellationAllocation(
        String sourceAccrualPointKey, long amount, int sequenceNo) {

    public PointCancellationAllocation {
        if (sourceAccrualPointKey == null || amount <= 0 || sequenceNo <= 0) {
            throw new IllegalArgumentException("사용취소 배분값을 확인해 주세요.");
        }
    }
}
