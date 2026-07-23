package com.musinsapayments.point.domain.use;

import com.musinsapayments.point.domain.ledger.PointLedgerDetail;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class PointUseCancellationAllocator {

    public List<PointCancellationAllocation> allocate(
            List<PointLedgerDetail> originalUseDetails,
            Map<String, Long> alreadyCanceledBySource,
            long requestedAmount) {

        // 사용 취소 금액 검증
        if (requestedAmount <= 0) {
            throw new IllegalArgumentException("취소 금액은 1 이상이어야 합니다.");
        }

        long remaining = requestedAmount;
        List<PointCancellationAllocation> result = new ArrayList<>();

        // 기존에 사용했던 포인트 원장 목록 가져와서 순서대로 취소 처리 (FIFO)
        for (PointLedgerDetail detail : originalUseDetails.stream()
                .sorted(Comparator.comparingInt(PointLedgerDetail::getSequenceNo))
                .toList()) {

            long canceled = alreadyCanceledBySource.getOrDefault(detail.getSourceAccrualPointKey(), 0L);
            if (canceled < 0 || canceled > detail.getAmount()) {
                throw new IllegalArgumentException("기취소 금액을 확인해 주세요.");
            }

            // 취소 가능 금액
            long cancelable = detail.getAmount() - canceled;
            if (cancelable <= 0) {
                continue;
            }

            long allocated = Math.min(cancelable, remaining);
            result.add(new PointCancellationAllocation(
                    detail.getSourceAccrualPointKey(), allocated, result.size() + 1));
            remaining -= allocated;

            // 전부 취소 됐다면 중단
            if (remaining == 0) {
                break;
            }
        }

        if (remaining != 0) {
            throw new IllegalStateException("사용취소 가능 금액을 초과했습니다.");
        }

        return List.copyOf(result);
    }
}
