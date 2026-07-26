package com.musinsapayments.point.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.support.PointTestFixture;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PointMutationResultTest {

    @Test
    void 만료_재적립은_balanceAfter가_있어도_외부_변경_응답으로_변환하지_않는다() {
        PointLedger refund = PointLedger.createExpiredUseRefund(
                PointTestFixture.CUSTOMER_ID, "E", "D", 1_000L, 1_400L,
                PointTestFixture.NOW.plusDays(7), PointTestFixture.NOW,
                LocalDate.of(2026, 7, 22));

        assertThatThrownBy(() -> PointMutationResult.from(refund))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
