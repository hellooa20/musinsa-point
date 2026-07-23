package com.musinsapayments.point.domain.use;

import com.musinsapayments.point.domain.ledger.AccrualTransactionType;
import com.musinsapayments.point.domain.ledger.PointLedger;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class PointUseAllocator {

    public List<PointAllocation> allocate(
            List<PointLedger> candidates, long requestedAmount, OffsetDateTime now) {

        // 사용 금액 검증
        if (requestedAmount <= 0) {
            throw new IllegalArgumentException("사용 금액은 1 이상이어야 합니다.");
        }

        // 사용 가능한 포인트 원장 정책에 따라 정렬 처리
        List<PointLedger> sorted = candidates.stream()
                .filter(it -> !it.isExpired(now) && it.getRemainingAmount() > 0)
                .sorted(Comparator
                        .comparing((PointLedger it) -> it.getTransactionType() != AccrualTransactionType.MANUAL)
                        .thenComparing(PointLedger::getExpiresAt)
                        .thenComparing(PointLedger::getOccurredAt)
                        .thenComparing(PointLedger::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();

        long remaining = requestedAmount;
        List<PointAllocation> result = new ArrayList<>();

        // 정렬된 포인트 원장 사용 처리
        for (PointLedger accrual : sorted) {
            if (remaining == 0) {
                break;
            }
            long allocated = Math.min(accrual.getRemainingAmount(), remaining);
            result.add(new PointAllocation(accrual.getPointKey(), allocated, result.size() + 1));
            remaining -= allocated;
        }

        if (remaining != 0) {
            throw new IllegalStateException("사용 가능한 포인트가 부족합니다.");
        }

        return List.copyOf(result);
    }
}
