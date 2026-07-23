package com.musinsapayments.point.domain.use;

import static com.musinsapayments.point.domain.ledger.AccrualTransactionType.MANUAL;
import static com.musinsapayments.point.domain.ledger.AccrualTransactionType.NORMAL;
import static com.musinsapayments.point.support.PointTestFixture.NOW;
import static com.musinsapayments.point.support.PointTestFixture.accrual;
import static org.assertj.core.api.Assertions.assertThat;

import com.musinsapayments.point.domain.ledger.PointLedger;
import java.util.List;
import org.junit.jupiter.api.Test;

class PointUseAllocatorTest {

    @Test
    void 수기_적립을_먼저_쓰고_같은_종류는_만료일_순으로_쓴다() {
        PointLedger normalLaterExpiry = accrual("B", NORMAL, 500L, 500L, NOW.plusDays(3));
        PointLedger manual = accrual("A", MANUAL, 100L, 100L, NOW.plusDays(30));
        PointLedger normalEarlierExpiry = accrual("C", NORMAL, 500L, 500L, NOW.plusDays(1));

        List<PointAllocation> result = new PointUseAllocator()
                .allocate(List.of(normalLaterExpiry, manual, normalEarlierExpiry), 800L, NOW);

        assertThat(result).containsExactly(
                new PointAllocation("A", 100L, 1),
                new PointAllocation("C", 500L, 2),
                new PointAllocation("B", 200L, 3));
    }
}
