package com.musinsapayments.point.domain.use;

public record PointAllocation(String accrualPointKey, long amount, int sequenceNo) {

    public PointAllocation {
        if (accrualPointKey == null || amount <= 0 || sequenceNo <= 0) {
            throw new IllegalArgumentException("사용 배분값을 확인해 주세요.");
        }
    }
}
