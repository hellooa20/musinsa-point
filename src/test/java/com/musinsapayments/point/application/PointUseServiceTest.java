package com.musinsapayments.point.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.musinsapayments.point.application.command.PointUseCommand;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PointUseServiceTest {

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

    private PointUseService service;

    @BeforeEach
    void setUp() {
        service = new PointUseService(
                policies, ledgers, details, idempotency, keys, clock,
                new PointProperties(100_000L, 365, 7, ZoneId.of("Asia/Seoul")));
    }

    @Test
    void 수기_A와_일반_B에서_1200을_배분한다() {
        PointLedger manualA = accrual("A", AccrualTransactionType.MANUAL, 1_000L, NOW.plusDays(30));
        PointLedger normalB = accrual("B", AccrualTransactionType.NORMAL, 500L, NOW.plusDays(1));
        prepareUse(1_500L, List.of(normalB, manualA));
        given(keys.generate()).willReturn("C");

        PointMutationResult result = service.use(command(1_200L));

        assertThat(result.pointKey()).isEqualTo("C");
        assertThat(result.balanceAfter()).isEqualTo(300L);
        assertThat(result.transactionDate()).isEqualTo(NOW.toLocalDate());
        assertThat(manualA.getRemainingAmount()).isZero();
        assertThat(normalB.getRemainingAmount()).isEqualTo(300L);
        assertSavedDetails("C", "A", 1_000L, "B", 200L);
        then(clock).should().instant();
        then(clock).shouldHaveNoMoreInteractions();
    }

    @Test
    void 만료된_적립은_배분에서_제외한다() {
        PointLedger expired = accrual("EXPIRED", AccrualTransactionType.MANUAL, 1_000L, NOW);
        PointLedger available = accrual("A", AccrualTransactionType.NORMAL, 1_000L, NOW.plusDays(1));
        prepareUse(1_000L, List.of(expired, available));
        given(keys.generate()).willReturn("U");

        service.use(command(1_000L));

        assertThat(expired.getRemainingAmount()).isEqualTo(1_000L);
        assertThat(available.getRemainingAmount()).isZero();
        assertSavedDetails("U", "A", 1_000L);
    }

    @Test
    void 동일_만료시각의_일반_적립은_조회_순서를_상세_순서로_보존한다() {
        PointLedger first = accrual("A", AccrualTransactionType.NORMAL, 500L, NOW.plusDays(1));
        PointLedger second = accrual("B", AccrualTransactionType.NORMAL, 500L, NOW.plusDays(1));
        prepareUse(1_000L, List.of(first, second));
        given(keys.generate()).willReturn("U");

        service.use(command(1_000L));

        assertSavedDetails("U", "A", 500L, "B", 500L);
    }

    @Test
    void 정책이_없으면_정책_부재_오류를_반환한다() {
        assertThatThrownBy(() -> service.use(command(1L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.POLICY_NOT_FOUND);
        then(clock).shouldHaveNoInteractions();
    }

    @Test
    void 주문번호가_이미_있으면_충돌_오류를_반환한다() {
        given(policies.findByCustomerIdForUpdate(CUSTOMER_ID)).willReturn(Optional.of(policy()));
        given(ledgers.findByOrderNumber("ORDER-1234")).willReturn(Optional.of(PointTestFixture.use("OLD", 100L)));

        assertThatThrownBy(() -> service.use(command(100L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.ORDER_NUMBER_CONFLICT);
        then(clock).shouldHaveNoInteractions();
    }

    @Test
    void 금액이_0이면_포인트_잔액_부족_오류를_반환한다() {
        given(clock.instant()).willReturn(INSTANT);
        given(policies.findByCustomerIdForUpdate(CUSTOMER_ID)).willReturn(Optional.of(policy()));
        given(ledgers.sumAvailableBalance(eq(CUSTOMER_ID), any())).willReturn(1_000L);

        assertThatThrownBy(() -> service.use(command(0L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.POINT_BALANCE_INSUFFICIENT);
        then(ledgers).should(never()).findSpendableAccruals(any(Long.class), any());
        then(ledgers).should(never()).save(any());
        then(details).shouldHaveNoInteractions();
    }

    @Test
    void 잔액이_부족하면_어떤_적립도_차감하지_않는다() {
        PointLedger accrual = accrual("A", AccrualTransactionType.NORMAL, 500L, NOW.plusDays(1));
        given(clock.instant()).willReturn(INSTANT);
        given(policies.findByCustomerIdForUpdate(CUSTOMER_ID)).willReturn(Optional.of(policy()));
        given(ledgers.sumAvailableBalance(eq(CUSTOMER_ID), any())).willReturn(500L);

        assertThatThrownBy(() -> service.use(command(501L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.POINT_BALANCE_INSUFFICIENT);
        assertThat(accrual.getRemainingAmount()).isEqualTo(500L);
        then(ledgers).should(never()).findSpendableAccruals(any(Long.class), any());
        then(ledgers).should(never()).save(any());
        then(details).shouldHaveNoInteractions();
    }

    @Test
    void allocator가_부족을_보고하면_포인트_잔액_부족으로_변환하고_차감하지_않는다() {
        PointLedger accrual = accrual("A", AccrualTransactionType.NORMAL, 500L, NOW.plusDays(1));
        prepareUse(1_000L, List.of(accrual));

        assertThatThrownBy(() -> service.use(command(1_000L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.POINT_BALANCE_INSUFFICIENT);
        assertThat(accrual.getRemainingAmount()).isEqualTo(500L);
        then(ledgers).should().findSpendableAccruals(CUSTOMER_ID, NOW);
        then(ledgers).should(never()).save(any());
        then(details).shouldHaveNoInteractions();
    }

    @Test
    void 같은_requestId의_정상_재생은_락을_얻지_않는다() {
        PointMutationResult replay = result("A", 1_000L, 0L);
        given(idempotency.findUseReplay(REQUEST_ID, CUSTOMER_ID, "ORDER-1234", 1_000L))
                .willReturn(Optional.of(replay));

        assertThat(service.use(command(1_000L))).isEqualTo(replay);

        then(policies).shouldHaveNoInteractions();
        then(ledgers).shouldHaveNoInteractions();
        then(details).shouldHaveNoInteractions();
        then(clock).shouldHaveNoInteractions();
    }

    @Test
    void 락_획득_후_재생이면_기존_결과만_반환한다() {
        PointMutationResult replay = result("A", 1_000L, 0L);
        given(idempotency.findUseReplay(REQUEST_ID, CUSTOMER_ID, "ORDER-1234", 1_000L))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(replay));
        given(policies.findByCustomerIdForUpdate(CUSTOMER_ID)).willReturn(Optional.of(policy()));

        assertThat(service.use(command(1_000L))).isEqualTo(replay);

        then(ledgers).shouldHaveNoInteractions();
        then(details).shouldHaveNoInteractions();
        then(clock).shouldHaveNoInteractions();
    }

    private void prepareUse(long currentBalance, List<PointLedger> candidates) {
        given(clock.instant()).willReturn(INSTANT);
        given(policies.findByCustomerIdForUpdate(CUSTOMER_ID)).willReturn(Optional.of(policy()));
        given(ledgers.sumAvailableBalance(eq(CUSTOMER_ID), any())).willReturn(currentBalance);
        given(ledgers.findSpendableAccruals(CUSTOMER_ID, NOW)).willReturn(candidates);
    }

    private PointUseCommand command(long amount) {
        return new PointUseCommand(REQUEST_ID, CUSTOMER_ID, "ORDER-1234", amount);
    }

    private PointLedger accrual(
            String pointKey, AccrualTransactionType type, long amount, OffsetDateTime expiresAt) {
        return PointTestFixture.accrual(pointKey, type, amount, amount, expiresAt);
    }

    private CustomerPointPolicy policy() {
        return PointTestFixture.policy(10_000L);
    }

    private PointMutationResult result(String pointKey, long amount, long balanceAfter) {
        return new PointMutationResult(
                pointKey, CUSTOMER_ID, PointType.USE, null, "ORDER-1234", amount, balanceAfter,
                NOW, NOW.toLocalDate(), null);
    }

    @SuppressWarnings("unchecked")
    private void assertSavedDetails(String pointKey, Object... values) {
        ArgumentCaptor<List<PointLedgerDetail>> captor = ArgumentCaptor.forClass(List.class);
        then(details).should().saveAll(captor.capture());
        List<PointLedgerDetail> saved = captor.getValue();
        assertThat(saved).hasSize(values.length / 2);
        long total = saved.stream().mapToLong(PointLedgerDetail::getAmount).sum();
        assertThat(total).isEqualTo(java.util.stream.IntStream.range(0, values.length / 2)
                .mapToLong(index -> (long) values[index * 2 + 1]).sum());
        for (int index = 0; index < saved.size(); index++) {
            PointLedgerDetail detail = saved.get(index);
            assertThat(detail.getPointKey()).isEqualTo(pointKey);
            assertThat(detail.getSourceAccrualPointKey()).isEqualTo(values[index * 2]);
            assertThat(detail.getTargetAccrualPointKey()).isNull();
            assertThat(detail.getAmount()).isEqualTo(values[index * 2 + 1]);
            assertThat(detail.getSequenceNo()).isEqualTo(index + 1);
        }
    }
}
