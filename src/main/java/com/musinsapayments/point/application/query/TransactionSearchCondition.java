package com.musinsapayments.point.application.query;

import com.musinsapayments.point.domain.ledger.PointType;
import java.time.LocalDate;

public record TransactionSearchCondition(
        long customerId,
        PointType pointType,
        LocalDate fromDate,
        LocalDate toDate,
        int page,
        int size) {

    public TransactionSearchCondition {
        if (customerId <= 0 || page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("조회 조건을 확인해 주세요.");
        }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("시작일은 종료일보다 늦을 수 없습니다.");
        }
    }
}
