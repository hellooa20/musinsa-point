package com.musinsapayments.point.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.musinsapayments.point.application.command.PointUseCancellationCommand;
import com.musinsapayments.point.config.PointProperties;
import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.domain.ledger.AccrualTransactionType;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.ledger.PointLedgerDetail;
import com.musinsapayments.point.domain.ledger.PointType;
import com.musinsapayments.point.domain.policy.CustomerPointPolicy;
import com.musinsapayments.point.repository.CustomerPointPolicyRepository;
import com.musinsapayments.point.repository.PointLedgerDetailRepository;
import com.musinsapayments.point.repository.PointLedgerRepository;
import com.musinsapayments.point.support.PointKeyGenerator;
import com.musinsapayments.point.support.PointTestFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PointUseCancellationServiceTest {

    private static final long CUSTOMER_ID = 100L;
    private static final UUID REQUEST_ID = PointTestFixture.uuid(1);
    private static final Instant INSTANT = Instant.parse("2026-07-22T01:00:00Z");
    private static final OffsetDateTime NOW = PointTestFixture.NOW;

    @Mock
    CustomerPointPolicyRepository policies;

    @Mock
    PointLedgerRepository ledgers;

    @Mock
    PointLedgerDetailRepository details;

    @Mock
    PointIdempotencyGuard idempotency;

    @Mock
    PointKeyGenerator keys;

    @Mock
    Clock clock;

    private PointUseCancellationService service;

    @BeforeEach
    void setUp() {
        service = new PointUseCancellationService(
                policies, ledgers, details, idempotency, keys, clock,
                new PointProperties(100_000L, 365, 7, ZoneId.of("Asia/Seoul")));
    }

    @Test
    void 만료된_A는_E로_재적립하고_미만료_B는_직접_복원한다() {
        PointLedger use = PointTestFixture.use("C", 1_200L);
        PointLedger expiredA = accrual("A", AccrualTransactionType.MANUAL, 1_000L, 0L, NOW);
        PointLedger liveB = accrual("B", AccrualTransactionType.NORMAL, 500L, 300L, NOW.plusDays(1));
        prepare(10_000L, 300L, use, List.of(
                detail("C", "A", 1_000L, 1), detail("C", "B", 200L, 2)), List.of(),
                List.of(expiredA, liveB));
        given(keys.generate()).willReturn("D", "E");

        PointMutationResult result = service.cancel(command("C", "ORDER-1234-CANCEL-1", 1_100L));

        assertThat(result.balanceAfter()).isEqualTo(1_400L);
        assertThat(result.transactionDate()).isEqualTo(NOW.toLocalDate());
        assertThat(liveB.getRemainingAmount()).isEqualTo(400L);
        then(ledgers).should().save(argThat(it -> it.getPointKey().equals("D")));
        then(ledgers).should().save(argThat(it -> it.getPointKey().equals("E")
                && it.getReferencePointKey().equals("D")
                && Objects.equals(it.getBalanceAfter(), 1_400L)));
        assertDetails(
                tuple("D", "A", "E", 1_000L, 1),
                tuple("D", "B", "B", 100L, 2),
                tuple("E", "E", "E", 1_000L, 1));
        then(clock).should().instant();
        then(clock).shouldHaveNoMoreInteractions();
    }

    @Test
    void 일부_취소는_FIFO_첫_상세부터_복원한다() {
        PointLedger use = PointTestFixture.use("C", 1_200L);
        PointLedger sourceA = accrual("A", AccrualTransactionType.MANUAL, 1_000L, 0L, NOW.plusDays(1));
        PointLedger sourceB = accrual("B", AccrualTransactionType.NORMAL, 500L, 300L, NOW.plusDays(1));
        prepare(10_000L, 300L, use, List.of(
                detail("C", "A", 1_000L, 1), detail("C", "B", 200L, 2)), List.of(),
                List.of(sourceA, sourceB));
        given(keys.generate()).willReturn("D");

        PointMutationResult result = service.cancel(command("C", "CANCEL-1", 400L));

        assertThat(result.amount()).isEqualTo(400L);
        assertThat(sourceA.getRemainingAmount()).isEqualTo(400L);
        assertThat(sourceB.getRemainingAmount()).isEqualTo(300L);
        assertDetails(tuple("D", "A", "A", 400L, 1));
    }

    @Test
    void 이전_부분_취소_뒤_남은_금액만_전액_취소한다() {
        PointLedger use = PointTestFixture.use("C", 1_200L);
        PointLedger sourceA = accrual("A", AccrualTransactionType.MANUAL, 1_000L, 400L, NOW.plusDays(1));
        PointLedger sourceB = accrual("B", AccrualTransactionType.NORMAL, 500L, 300L, NOW.plusDays(1));
        prepare(10_000L, 700L, use, List.of(
                detail("C", "A", 1_000L, 1), detail("C", "B", 200L, 2)),
                canceled("A", 400L), List.of(sourceA, sourceB));
        given(keys.generate()).willReturn("D");

        PointMutationResult result = service.cancel(command("C", "CANCEL-2", 800L));

        assertThat(result.balanceAfter()).isEqualTo(1_500L);
        assertThat(sourceA.getRemainingAmount()).isEqualTo(1_000L);
        assertThat(sourceB.getRemainingAmount()).isEqualTo(500L);
        assertDetails(tuple("D", "A", "A", 600L, 1), tuple("D", "B", "B", 200L, 2));
    }

    @Test
    void 누적_취소_가능_금액을_초과하면_아무것도_복원하지_않는다() {
        PointLedger use = PointTestFixture.use("C", 1_000L);
        PointLedger source = accrual("A", AccrualTransactionType.NORMAL, 1_000L, 800L, NOW.plusDays(1));
        prepare(10_000L, 800L, use, List.of(detail("C", "A", 1_000L, 1)),
                canceled("A", 800L), List.of(source));

        assertThatThrownBy(() -> service.cancel(command("C", "CANCEL-2", 300L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.USE_CANCEL_AMOUNT_EXCEEDED);

        assertThat(source.getRemainingAmount()).isEqualTo(800L);
        then(ledgers).should(never()).save(any());
        then(details).should(never()).saveAll(any());
    }

    @Test
    void 손상된_누적_취소_집계는_무결성_오류이고_저장이나_복원이_없다() {
        PointLedger use = PointTestFixture.use("C", 100L);
        PointLedger source = accrual("A", AccrualTransactionType.NORMAL, 100L, 0L, NOW.plusDays(1));
        prepare(10_000L, 0L, use, List.of(detail("C", "A", 100L, 1)),
                canceled("A", 101L), List.of(source));

        assertThatThrownBy(() -> service.cancel(command("C", "CANCEL-1", 1L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.DATA_INTEGRITY_VIOLATION);

        assertThat(source.getRemainingAmount()).isZero();
        then(ledgers).should(never()).save(any());
        then(details).should(never()).saveAll(any());
    }

    @Test
    void 만료된_재적립_E도_다시_E로_재적립한다() {
        PointLedger use = PointTestFixture.use("C", 700L);
        PointLedger expiredRefund = PointLedger.createExpiredUseRefund(
                CUSTOMER_ID, "E1", "OLD-D", 700L, 700L,
                NOW, NOW.minusDays(7), NOW.toLocalDate());
        expiredRefund.consume(700L, NOW.minusNanos(1));
        prepare(10_000L, 0L, use, List.of(detail("C", "E1", 700L, 1)), List.of(), List.of(expiredRefund));
        given(keys.generate()).willReturn("D", "E2");

        service.cancel(command("C", "CANCEL-1", 700L));

        then(ledgers).should().save(argThat(it -> it.getPointKey().equals("E2")
                && it.getReferencePointKey().equals("D")
                && it.getTransactionType() == AccrualTransactionType.EXPIRED_USE_REFUND));
        assertDetails(tuple("D", "E1", "E2", 700L, 1), tuple("E2", "E2", "E2", 700L, 1));
    }

    @Test
    void 여러_만료_source는_source별로_재적립_원장을_만든다() {
        PointLedger use = PointTestFixture.use("C", 1_000L);
        PointLedger expiredA = accrual("A", AccrualTransactionType.NORMAL, 500L, 0L, NOW);
        PointLedger expiredB = accrual("B", AccrualTransactionType.MANUAL, 500L, 0L, NOW);
        prepare(10_000L, 0L, use, List.of(detail("C", "A", 500L, 1), detail("C", "B", 500L, 2)),
                List.of(), List.of(expiredA, expiredB));
        given(keys.generate()).willReturn("D", "E1", "E2");

        service.cancel(command("C", "CANCEL-1", 1_000L));

        then(ledgers).should().save(argThat(it -> it.getPointKey().equals("E1")
                && Objects.equals(it.getBalanceAfter(), 1_000L)));
        then(ledgers).should().save(argThat(it -> it.getPointKey().equals("E2")
                && Objects.equals(it.getBalanceAfter(), 1_000L)));
        assertDetails(
                tuple("D", "A", "E1", 500L, 1), tuple("D", "B", "E2", 500L, 2),
                tuple("E1", "E1", "E1", 500L, 1), tuple("E2", "E2", "E2", 500L, 1));
    }

    @Test
    void 취소로_보유_한도를_넘으면_어떤_원본도_복원하지_않는다() {
        PointLedger use = PointTestFixture.use("C", 500L);
        PointLedger source = accrual("A", AccrualTransactionType.NORMAL, 500L, 0L, NOW.plusDays(1));
        prepare(1_000L, 900L, use, List.of(detail("C", "A", 500L, 1)), List.of(), List.of(source));

        assertThatThrownBy(() -> service.cancel(command("C", "CANCEL-1", 101L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.HOLDING_LIMIT_EXCEEDED);

        assertThat(source.getRemainingAmount()).isZero();
        then(ledgers).should(never()).save(any());
        then(details).shouldHaveNoInteractions();
    }

    @Test
    void 영_또는_덧셈_오버플로우_취소는_보유_한도_오류다() {
        PointLedger use = PointTestFixture.use("C", 1L);
        prepare(10_000L, Long.MAX_VALUE, use, List.of(detail("C", "A", 1L, 1)), List.of(), List.of());

        assertThatThrownBy(() -> service.cancel(command("C", "CANCEL-1", 1L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.HOLDING_LIMIT_EXCEEDED);

        prepare(10_000L, 0L, use, List.of(detail("C", "A", 1L, 1)), List.of(), List.of());
        assertThatThrownBy(() -> service.cancel(command("C", "CANCEL-2", 0L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.HOLDING_LIMIT_EXCEEDED);
    }

    @Test
    void 취소_주문번호가_이미_있으면_충돌_오류를_반환한다() {
        given(policies.findByCustomerIdForUpdate(CUSTOMER_ID)).willReturn(Optional.of(policy(10_000L)));
        given(ledgers.findByOrderNumber("CANCEL-1")).willReturn(Optional.of(PointTestFixture.use("OLD", 1L)));

        assertThatThrownBy(() -> service.cancel(command("C", "CANCEL-1", 1L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.ORDER_NUMBER_CONFLICT);
        then(clock).shouldHaveNoInteractions();
    }

    @Test
    void source가_없거나_적립이_아니면_무결성_오류이고_미리_복원하지_않는다() {
        PointLedger use = PointTestFixture.use("C", 600L);
        PointLedger liveA = accrual("A", AccrualTransactionType.NORMAL, 300L, 0L, NOW.plusDays(1));
        prepare(10_000L, 0L, use, List.of(detail("C", "A", 300L, 1), detail("C", "B", 300L, 2)),
                List.of(), List.of(liveA));
        given(keys.generate()).willReturn("D");

        assertThatThrownBy(() -> service.cancel(command("C", "CANCEL-1", 600L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.DATA_INTEGRITY_VIOLATION);

        assertThat(liveA.getRemainingAmount()).isZero();
        then(details).should(never()).saveAll(any());
    }

    @Test
    void source가_적립이_아니면_무결성_오류를_반환한다() {
        PointLedger use = PointTestFixture.use("C", 100L);
        PointLedger nonAccrualSource = PointTestFixture.use("X", 100L);
        prepare(10_000L, 0L, use, List.of(detail("C", "X", 100L, 1)),
                List.of(), List.of(nonAccrualSource));
        given(keys.generate()).willReturn("D");

        assertThatThrownBy(() -> service.cancel(command("C", "CANCEL-1", 100L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.DATA_INTEGRITY_VIOLATION);

        then(details).should(never()).saveAll(any());
    }

    @Test
    void 같은_요청의_fast_replay는_락을_얻지_않는다() {
        PointMutationResult replay = result("D", 100L, 100L);
        given(idempotency.findUseCancellationReplay(
                REQUEST_ID, CUSTOMER_ID, "C", "CANCEL-1", 100L)).willReturn(Optional.of(replay));

        assertThat(service.cancel(command("C", "CANCEL-1", 100L))).isEqualTo(replay);

        then(policies).shouldHaveNoInteractions();
        then(ledgers).shouldHaveNoInteractions();
        then(details).shouldHaveNoInteractions();
        then(clock).shouldHaveNoInteractions();
    }

    @Test
    void 락_획득_후_replay면_기존_결과만_반환한다() {
        PointMutationResult replay = result("D", 100L, 100L);
        given(idempotency.findUseCancellationReplay(
                REQUEST_ID, CUSTOMER_ID, "C", "CANCEL-1", 100L))
                .willReturn(Optional.empty()).willReturn(Optional.of(replay));
        given(policies.findByCustomerIdForUpdate(CUSTOMER_ID)).willReturn(Optional.of(policy(10_000L)));

        assertThat(service.cancel(command("C", "CANCEL-1", 100L))).isEqualTo(replay);

        then(ledgers).shouldHaveNoInteractions();
        then(details).shouldHaveNoInteractions();
        then(clock).shouldHaveNoInteractions();
    }

    private void prepare(
            long holdingLimit, long currentBalance, PointLedger use, List<PointLedgerDetail> originalDetails,
            List<Object[]> canceledRows, List<PointLedger> sources) {
        given(clock.instant()).willReturn(INSTANT);
        given(policies.findByCustomerIdForUpdate(CUSTOMER_ID)).willReturn(Optional.of(policy(holdingLimit)));
        given(ledgers.findByPointKeyAndCustomerId("C", CUSTOMER_ID)).willReturn(Optional.of(use));
        given(ledgers.sumAvailableBalance(eq(CUSTOMER_ID), any())).willReturn(currentBalance);
        org.mockito.Mockito.lenient().when(details.findByPointKeyOrderBySequenceNoAsc("C"))
                .thenReturn(originalDetails);
        org.mockito.Mockito.lenient().when(details.sumCanceledAmountBySource("C"))
                .thenReturn(canceledRows);
        org.mockito.Mockito.lenient().when(ledgers.findAllByPointKeyIn(any())).thenReturn(sources);
    }

    private PointUseCancellationCommand command(String usePointKey, String cancelOrderNumber, long amount) {
        return new PointUseCancellationCommand(
                REQUEST_ID, CUSTOMER_ID, usePointKey, cancelOrderNumber, amount);
    }

    private CustomerPointPolicy policy(long holdingLimit) {
        return PointTestFixture.policy(holdingLimit);
    }

    private PointLedger accrual(
            String pointKey, AccrualTransactionType type, long amount,
            long remainingAmount, OffsetDateTime expiresAt) {
        PointLedger ledger = PointTestFixture.accrual(pointKey, type, amount, amount, expiresAt);
        if (remainingAmount < amount) {
            ledger.consume(amount - remainingAmount, expiresAt.minusNanos(1));
        }
        return ledger;
    }

    private PointLedgerDetail detail(String pointKey, String sourceKey, long amount, int sequenceNo) {
        return PointTestFixture.detail(pointKey, sourceKey, null, amount, sequenceNo);
    }

    private List<Object[]> canceled(String sourceKey, long amount) {
        return List.<Object[]>of(new Object[] {sourceKey, amount});
    }

    private PointMutationResult result(String pointKey, long amount, long balanceAfter) {
        return new PointMutationResult(
                pointKey, CUSTOMER_ID, PointType.USE_CANCEL, "C", "CANCEL-1", amount, balanceAfter,
                NOW, NOW.toLocalDate(), null);
    }

    @SuppressWarnings("unchecked")
    private void assertDetails(org.assertj.core.groups.Tuple... expected) {
        ArgumentCaptor<List<PointLedgerDetail>> captor = ArgumentCaptor.forClass(List.class);
        then(details).should().saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(
                PointLedgerDetail::getPointKey,
                PointLedgerDetail::getSourceAccrualPointKey,
                PointLedgerDetail::getTargetAccrualPointKey,
                PointLedgerDetail::getAmount,
                PointLedgerDetail::getSequenceNo).containsExactlyInAnyOrder(expected);
    }
}
