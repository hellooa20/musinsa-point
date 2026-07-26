package com.musinsapayments.point.domain.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class PointLedgerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-22T10:00:00+09:00");

    @Test
    void 적립은_잔액을_차감하고_복원한다() {
        PointLedger accrual = PointLedger.createAccrual(
                100L, "A", "request-a", AccrualTransactionType.NORMAL, null,
                1_000L, 1_000L, NOW.plusDays(365), NOW, LocalDate.of(2026, 7, 22));

        accrual.consume(300L, NOW);
        accrual.restore(100L, NOW.plusMinutes(1));

        assertThat(accrual.getRemainingAmount()).isEqualTo(800L);
        assertThat(accrual.accrualStatus(NOW, false)).isEqualTo(AccrualStatus.PARTIALLY_AVAILABLE);
    }

    @Test
    void 만료_경계와_같은_시각부터_만료다() {
        PointLedger accrual = PointLedger.createAccrual(
                100L, "A", "request-a", AccrualTransactionType.NORMAL, null,
                1_000L, 1_000L, NOW, NOW.minusDays(1), LocalDate.of(2026, 7, 21));

        assertThat(accrual.isExpired(NOW)).isTrue();
        assertThat(accrual.accrualStatus(NOW, false)).isEqualTo(AccrualStatus.EXPIRED);
    }

    @Test
    void 적립이_아닌_원장은_차감할_수_없다() {
        PointLedger use = PointLedger.createUse(
                100L, "C", "request-c", "ORDER-1", 500L, 500L, NOW,
                LocalDate.of(2026, 7, 22));

        assertThatThrownBy(() -> use.consume(1L, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 일반과_수기_적립은_원본_포인트를_참조할_수_없다() {
        assertThatThrownBy(() -> PointLedger.createAccrual(
                100L, "A", "request-a", AccrualTransactionType.NORMAL, "use-cancel-a",
                1_000L, 1_000L, NOW.plusDays(365), NOW, LocalDate.of(2026, 7, 22)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> PointLedger.createAccrual(
                100L, "B", "request-b", AccrualTransactionType.MANUAL, "use-cancel-b",
                1_000L, 1_000L, NOW.plusDays(365), NOW, LocalDate.of(2026, 7, 22)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 만료_재적립은_전용_팩토리로만_생성한다() {
        assertThatThrownBy(() -> PointLedger.createAccrual(
                100L, "A", null, AccrualTransactionType.EXPIRED_USE_REFUND, "use-cancel-a",
                1_000L, 0L, NOW.plusDays(7), NOW, LocalDate.of(2026, 7, 22)))
                .isInstanceOf(IllegalArgumentException.class);

        PointLedger refund = PointLedger.createExpiredUseRefund(
                100L, "refund-a", "use-cancel-a", 1_000L,
                1_400L, NOW.plusDays(7), NOW, LocalDate.of(2026, 7, 22));

        assertThat(refund.getTransactionType()).isEqualTo(AccrualTransactionType.EXPIRED_USE_REFUND);
        assertThat(refund.getReferencePointKey()).isEqualTo("use-cancel-a");
        assertThat(refund.getRequestId()).isNull();
        assertThat(refund.getBalanceAfter()).isEqualTo(1_400L);
    }

    @Test
    void 외부_원장_팩토리는_비어있는_requestId를_허용하지_않는다() {
        assertThatThrownBy(() -> PointLedger.createAccrual(
                100L, "A", " ", AccrualTransactionType.NORMAL, null,
                1_000L, 1_000L, NOW.plusDays(365), NOW, LocalDate.of(2026, 7, 22)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointLedger.createAccrualCancellation(
                100L, "B", " ", "A", 1_000L, 0L, NOW, LocalDate.of(2026, 7, 22)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointLedger.createUse(
                100L, "C", " ", "ORDER-1", 1_000L, 0L, NOW,
                LocalDate.of(2026, 7, 22)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointLedger.createUseCancellation(
                100L, "D", " ", "C", "ORDER-CANCEL-1", 1_000L, 1_000L, NOW,
                LocalDate.of(2026, 7, 22)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
