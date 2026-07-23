package com.musinsapayments.point.domain.policy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class CustomerPointPolicyTest {

    @Test
    void 현재_잔액보다_낮게_한도를_변경할_수_없다() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-22T10:00:00+09:00");
        CustomerPointPolicy policy = CustomerPointPolicy.create(100L, 10_000L, now);

        assertThatThrownBy(() -> policy.changeHoldingLimit(999L, 1_000L, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("보유 한도는 현재 잔액보다 작을 수 없습니다.");
    }
}
