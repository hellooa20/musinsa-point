package com.musinsapayments.point.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.domain.ledger.AccrualTransactionType;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.repository.PointLedgerRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PointIdempotencyGuardTest {

    @Mock
    PointLedgerRepository ledgers;

    private PointIdempotencyGuard guard;

    @BeforeEach
    void setUp() {
        guard = new PointIdempotencyGuard(ledgers);
    }

    @Test
    void 같은_적립_요청은_최초_balanceAfter를_재생한다() {
        UUID requestId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        PointLedger existing = accrualWithSnapshot(requestId.toString(), 1_000L, 1_000L, 365);
        existing.consume(500L, existing.getOccurredAt().plusHours(1));
        given(ledgers.findByRequestId(requestId.toString())).willReturn(Optional.of(existing));

        PointMutationResult replay = guard.findAccrualReplay(
                requestId, 100L, 1_000L, 365, AccrualTransactionType.NORMAL).orElseThrow();

        assertThat(replay.balanceAfter()).isEqualTo(1_000L);
        assertThat(replay.toString()).doesNotContain("remainingAmount");
    }

    @Test
    void 같은_requestId를_다른_입력으로_재사용하면_충돌이다() {
        UUID requestId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        given(ledgers.findByRequestId(requestId.toString()))
                .willReturn(Optional.of(accrualWithSnapshot(requestId.toString(), 1_000L, 1_000L, 365)));

        assertThatThrownBy(() -> guard.findAccrualReplay(
                requestId, 100L, 999L, 365, AccrualTransactionType.NORMAL))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.REQUEST_ID_CONFLICT);
    }

    @Test
    void 원장이_없으면_재생_결과가_없다() {
        UUID requestId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        given(ledgers.findByRequestId(requestId.toString())).willReturn(Optional.empty());

        assertThat(guard.findAccrualReplay(
                requestId, 100L, 1_000L, 365, AccrualTransactionType.NORMAL)).isEmpty();
    }

    @Test
    void 같은_적립취소_요청은_최초_응답을_재생한다() {
        UUID requestId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        PointLedger existing = PointLedger.createAccrualCancellation(
                100L, "POINT-2", requestId.toString(), "ACCRUAL-1", 1_000L, 0L,
                occurredAt(), transactionDate());
        given(ledgers.findByRequestId(requestId.toString())).willReturn(Optional.of(existing));

        PointMutationResult replay = guard.findAccrualCancellationReplay(
                requestId, 100L, "ACCRUAL-1").orElseThrow();

        assertThat(replay.pointKey()).isEqualTo("POINT-2");
    }

    @Test
    void 적립취소의_원본_키가_다르면_충돌이다() {
        UUID requestId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        given(ledgers.findByRequestId(requestId.toString())).willReturn(Optional.of(
                PointLedger.createAccrualCancellation(
                        100L, "POINT-2", requestId.toString(), "ACCRUAL-1", 1_000L, 0L,
                        occurredAt(), transactionDate())));

        assertThatThrownBy(() -> guard.findAccrualCancellationReplay(
                requestId, 100L, "ACCRUAL-2"))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.REQUEST_ID_CONFLICT);
    }

    @Test
    void 같은_사용_요청은_최초_응답을_재생한다() {
        UUID requestId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        PointLedger existing = PointLedger.createUse(
                100L, "POINT-3", requestId.toString(), "ORDER-1", 700L, 300L,
                occurredAt(), transactionDate());
        given(ledgers.findByRequestId(requestId.toString())).willReturn(Optional.of(existing));

        PointMutationResult replay = guard.findUseReplay(
                requestId, 100L, "ORDER-1", 700L).orElseThrow();

        assertThat(replay.pointKey()).isEqualTo("POINT-3");
    }

    @Test
    void 사용의_주문번호가_다르면_충돌이다() {
        UUID requestId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        given(ledgers.findByRequestId(requestId.toString())).willReturn(Optional.of(
                PointLedger.createUse(
                        100L, "POINT-3", requestId.toString(), "ORDER-1", 700L, 300L,
                        occurredAt(), transactionDate())));

        assertThatThrownBy(() -> guard.findUseReplay(requestId, 100L, "ORDER-2", 700L))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.REQUEST_ID_CONFLICT);
    }

    @Test
    void 같은_사용취소_요청은_최초_응답을_재생한다() {
        UUID requestId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        PointLedger existing = PointLedger.createUseCancellation(
                100L, "POINT-4", requestId.toString(), "USE-1", "CANCEL-ORDER-1", 700L,
                1_000L, occurredAt(), transactionDate());
        given(ledgers.findByRequestId(requestId.toString())).willReturn(Optional.of(existing));

        PointMutationResult replay = guard.findUseCancellationReplay(
                requestId, 100L, "USE-1", "CANCEL-ORDER-1", 700L).orElseThrow();

        assertThat(replay.pointKey()).isEqualTo("POINT-4");
    }

    @Test
    void 사용취소의_원본_키가_다르면_충돌이다() {
        UUID requestId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        given(ledgers.findByRequestId(requestId.toString())).willReturn(Optional.of(
                PointLedger.createUseCancellation(
                        100L, "POINT-4", requestId.toString(), "USE-1", "CANCEL-ORDER-1", 700L,
                        1_000L, occurredAt(), transactionDate())));

        assertThatThrownBy(() -> guard.findUseCancellationReplay(
                requestId, 100L, "USE-2", "CANCEL-ORDER-1", 700L))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.REQUEST_ID_CONFLICT);
    }

    private PointLedger accrualWithSnapshot(
            String requestId, long amount, long balanceAfter, int validityDays) {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-07-22T10:00:00+09:00");
        return PointLedger.createAccrual(
                100L, "POINT-1", requestId, AccrualTransactionType.NORMAL, null,
                amount, balanceAfter, occurredAt.plusDays(validityDays), occurredAt,
                LocalDate.of(2026, 7, 22));
    }

    private OffsetDateTime occurredAt() {
        return OffsetDateTime.parse("2026-07-22T10:00:00+09:00");
    }

    private LocalDate transactionDate() {
        return LocalDate.of(2026, 7, 22);
    }
}
