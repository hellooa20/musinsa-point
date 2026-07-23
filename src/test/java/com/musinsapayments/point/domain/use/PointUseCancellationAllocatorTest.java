package com.musinsapayments.point.domain.use;

import static com.musinsapayments.point.support.PointTestFixture.detail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.musinsapayments.point.domain.ledger.PointLedgerDetail;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PointUseCancellationAllocatorTest {

    @Test
    void 사용취소는_원본_사용_상세의_FIFO로_배분한다() {
        List<PointLedgerDetail> details = List.of(
                detail("C", "B", null, 200L, 2),
                detail("C", "A", null, 1_000L, 1));

        List<PointCancellationAllocation> result = new PointUseCancellationAllocator()
                .allocate(details, Map.of("A", 0L, "B", 0L), 1_100L);

        assertThat(result).containsExactly(
                new PointCancellationAllocation("A", 1_000L, 1),
                new PointCancellationAllocation("B", 100L, 2));
    }

    @Test
    void 소스별_기취소_금액이_음수이면_예외가_발생한다() {
        List<PointLedgerDetail> details = List.of(
                detail("C", "A", null, 1_000L, 1),
                detail("C", "B", null, 500L, 2));

        assertThatThrownBy(() -> new PointUseCancellationAllocator()
                .allocate(details, Map.of("A", -1L, "B", 0L), 500L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 소스별_기취소_금액이_원본_금액을_초과하면_예외가_발생한다() {
        List<PointLedgerDetail> details = List.of(
                detail("C", "A", null, 1_000L, 1),
                detail("C", "B", null, 500L, 2));

        assertThatThrownBy(() -> new PointUseCancellationAllocator()
                .allocate(details, Map.of("A", 1_001L, "B", 0L), 500L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
