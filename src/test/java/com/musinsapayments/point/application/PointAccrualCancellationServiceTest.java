package com.musinsapayments.point.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.musinsapayments.point.application.command.AccrualCancellationCommand;
import com.musinsapayments.point.config.PointProperties;
import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.domain.ledger.AccrualTransactionType;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.ledger.PointType;
import com.musinsapayments.point.domain.policy.CustomerPointPolicy;
import com.musinsapayments.point.repository.CustomerPointPolicyRepository;
import com.musinsapayments.point.repository.PointLedgerDetailRepository;
import com.musinsapayments.point.repository.PointLedgerRepository;
import com.musinsapayments.point.support.PointKeyGenerator;
import com.musinsapayments.point.support.PointTestFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PointAccrualCancellationServiceTest {

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

    private PointAccrualCancellationService service;

    @BeforeEach
    void setUp() {
        service = new PointAccrualCancellationService(
                policies, ledgers, details, idempotency, keys, clock,
                new PointProperties(100_000L, 365, 7, ZoneId.of("Asia/Seoul")));
    }

    @Test
    void 전액이_남은_미만료_적립만_취소한다() {
        PointLedger accrual = accrual("A", AccrualTransactionType.NORMAL, 1_000L, 1_000L, NOW.plusDays(1));
        given(clock.instant()).willReturn(INSTANT);
        given(policies.findByCustomerIdForUpdate(CUSTOMER_ID)).willReturn(Optional.of(policy()));
        given(ledgers.findByPointKeyAndCustomerId("A", CUSTOMER_ID)).willReturn(Optional.of(accrual));
        given(ledgers.existsByReferencePointKeyAndPointType("A", PointType.ACCRUAL_CANCEL)).willReturn(false);
        given(ledgers.sumAvailableBalance(eq(CUSTOMER_ID), any())).willReturn(1_000L);
        given(keys.generate()).willReturn("G");

        PointMutationResult result = service.cancel(
                new AccrualCancellationCommand(REQUEST_ID, CUSTOMER_ID, "A"));

        assertThat(result.amount()).isEqualTo(1_000L);
        assertThat(result.balanceAfter()).isZero();
        assertThat(result.transactionDate()).isEqualTo(NOW.toLocalDate());
        assertThat(accrual.getRemainingAmount()).isZero();
        then(clock).should().instant();
        then(clock).shouldHaveNoMoreInteractions();
        then(details).should().save(org.mockito.ArgumentMatchers.argThat(it ->
                it.getPointKey().equals("G")
                        && it.getSourceAccrualPointKey().equals("A")
                        && it.getTargetAccrualPointKey() == null));
    }

    @Test
    void 일부가_사용된_적립은_취소할_수_없다() {
        PointLedger accrual = accrual("A", AccrualTransactionType.NORMAL, 1_000L, 999L, NOW.plusDays(1));
        prepareAccrual(accrual);

        assertCancelNotAllowed("A");
    }

    @Test
    void 현재_잔액이_원본_적립보다_작으면_취소할_수_없다() {
        PointLedger accrual = accrual("A", AccrualTransactionType.NORMAL, 1_000L, 1_000L, NOW.plusDays(1));
        prepareAccrual(accrual);
        given(ledgers.sumAvailableBalance(eq(CUSTOMER_ID), any())).willReturn(999L);

        assertCancelNotAllowed("A");

        assertThat(accrual.getRemainingAmount()).isEqualTo(1_000L);
        then(ledgers).should(org.mockito.Mockito.never()).save(any());
        then(details).shouldHaveNoInteractions();
        then(keys).shouldHaveNoInteractions();
    }

    @Test
    void 만료된_적립은_취소할_수_없다() {
        PointLedger accrual = accrual("A", AccrualTransactionType.NORMAL, 1_000L, 1_000L, NOW);
        prepareAccrual(accrual);

        assertCancelNotAllowed("A");
    }

    @Test
    void 이미_취소된_적립은_취소할_수_없다() {
        PointLedger accrual = accrual("A", AccrualTransactionType.NORMAL, 1_000L, 1_000L, NOW.plusDays(1));
        prepareAccrual(accrual);
        given(ledgers.existsByReferencePointKeyAndPointType("A", PointType.ACCRUAL_CANCEL)).willReturn(true);

        assertCancelNotAllowed("A");
    }

    @Test
    void 다른_고객의_적립은_찾을_수_없다() {
        given(policies.findByCustomerIdForUpdate(CUSTOMER_ID)).willReturn(Optional.of(policy()));
        given(ledgers.findByPointKeyAndCustomerId("A", CUSTOMER_ID)).willReturn(Optional.empty());
        given(clock.instant()).willReturn(INSTANT);

        assertThatThrownBy(() -> service.cancel(command("A")))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.POINT_NOT_FOUND);
    }

    @Test
    void 적립이_아닌_원장은_취소할_수_없다() {
        PointLedger use = PointLedger.createUse(
                CUSTOMER_ID, "U", REQUEST_ID.toString(), "ORDER-1", 1_000L, 0L, NOW, NOW.toLocalDate());
        given(policies.findByCustomerIdForUpdate(CUSTOMER_ID)).willReturn(Optional.of(policy()));
        given(ledgers.findByPointKeyAndCustomerId("U", CUSTOMER_ID)).willReturn(Optional.of(use));
        given(clock.instant()).willReturn(INSTANT);

        assertThatThrownBy(() -> service.cancel(command("U")))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.POINT_NOT_FOUND);
    }

    @Test
    void 전액_사용_후_복원된_적립은_취소할_수_있다() {
        PointLedger accrual = accrual("A", AccrualTransactionType.NORMAL, 1_000L, 0L, NOW.plusDays(1));
        accrual.restore(1_000L, NOW.plusHours(1));
        prepareAccrual(accrual);
        given(ledgers.sumAvailableBalance(eq(CUSTOMER_ID), any())).willReturn(1_000L);
        given(keys.generate()).willReturn("G");

        PointMutationResult result = service.cancel(command("A"));

        assertThat(result.amount()).isEqualTo(1_000L);
        assertThat(accrual.getRemainingAmount()).isZero();
    }

    @ParameterizedTest
    @MethodSource("cancellableAccrualTypes")
    void 모든_적립_거래_타입은_동일한_전액_취소_규칙을_적용한다(AccrualTransactionType transactionType) {
        PointLedger accrual = accrual("A", transactionType, 1_000L, 1_000L, NOW.plusDays(1));
        prepareAccrual(accrual);
        given(ledgers.sumAvailableBalance(eq(CUSTOMER_ID), any())).willReturn(1_000L);
        given(keys.generate()).willReturn("G");

        PointMutationResult result = service.cancel(command("A"));

        assertThat(result.pointType()).isEqualTo(PointType.ACCRUAL_CANCEL);
        assertThat(accrual.getRemainingAmount()).isZero();
    }

    @Test
    void 같은_요청의_fast_replay는_락을_얻지_않는다() {
        PointMutationResult replay = replay();
        given(idempotency.findAccrualCancellationReplay(REQUEST_ID, CUSTOMER_ID, "A"))
                .willReturn(Optional.of(replay));

        PointMutationResult result = service.cancel(command("A"));

        assertThat(result).isEqualTo(replay);
        then(policies).shouldHaveNoInteractions();
        then(clock).shouldHaveNoInteractions();
        then(ledgers).shouldHaveNoInteractions();
        then(details).shouldHaveNoInteractions();
    }

    @Test
    void 락_획득_후_replay면_기존_결과만_반환한다() {
        PointMutationResult replay = replay();
        given(idempotency.findAccrualCancellationReplay(REQUEST_ID, CUSTOMER_ID, "A"))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(replay));
        given(policies.findByCustomerIdForUpdate(CUSTOMER_ID)).willReturn(Optional.of(policy()));

        PointMutationResult result = service.cancel(command("A"));

        assertThat(result).isEqualTo(replay);
        then(clock).shouldHaveNoInteractions();
        then(ledgers).shouldHaveNoInteractions();
        then(details).shouldHaveNoInteractions();
    }

    @Test
    void 정책이_없으면_정책_부재_오류를_반환한다() {
        assertThatThrownBy(() -> service.cancel(command("A")))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.POLICY_NOT_FOUND);
        then(clock).shouldHaveNoInteractions();
    }

    private void prepareAccrual(PointLedger accrual) {
        given(clock.instant()).willReturn(INSTANT);
        given(policies.findByCustomerIdForUpdate(CUSTOMER_ID)).willReturn(Optional.of(policy()));
        given(ledgers.findByPointKeyAndCustomerId(accrual.getPointKey(), CUSTOMER_ID))
                .willReturn(Optional.of(accrual));
        given(ledgers.existsByReferencePointKeyAndPointType(
                accrual.getPointKey(), PointType.ACCRUAL_CANCEL)).willReturn(false);
    }

    private void assertCancelNotAllowed(String pointKey) {
        assertThatThrownBy(() -> service.cancel(command(pointKey)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.ACCRUAL_CANCEL_NOT_ALLOWED);
    }

    private AccrualCancellationCommand command(String pointKey) {
        return new AccrualCancellationCommand(REQUEST_ID, CUSTOMER_ID, pointKey);
    }

    private PointLedger accrual(
            String pointKey, AccrualTransactionType type, long amount,
            long remainingAmount, OffsetDateTime expiresAt) {
        PointLedger ledger = type == AccrualTransactionType.EXPIRED_USE_REFUND
                ? PointLedger.createExpiredUseRefund(
                        CUSTOMER_ID, pointKey, "USE-CANCEL-1", amount, amount, expiresAt,
                        NOW, LocalDate.of(2026, 7, 22))
                : PointLedger.createAccrual(
                        CUSTOMER_ID, pointKey, UUID.randomUUID().toString(), type, null,
                        amount, amount, expiresAt, NOW, LocalDate.of(2026, 7, 22));
        if (remainingAmount < amount) {
            ledger.consume(amount - remainingAmount, NOW);
        }
        return ledger;
    }

    private CustomerPointPolicy policy() {
        return CustomerPointPolicy.create(CUSTOMER_ID, 10_000L, NOW);
    }

    private PointMutationResult replay() {
        return new PointMutationResult(
                "G", CUSTOMER_ID, PointType.ACCRUAL_CANCEL, "A", null, 1_000L, 0L,
                NOW, NOW.toLocalDate(), null);
    }

    private static Stream<Arguments> cancellableAccrualTypes() {
        return Stream.of(
                Arguments.of(AccrualTransactionType.NORMAL),
                Arguments.of(AccrualTransactionType.MANUAL),
                Arguments.of(AccrualTransactionType.EXPIRED_USE_REFUND));
    }
}
