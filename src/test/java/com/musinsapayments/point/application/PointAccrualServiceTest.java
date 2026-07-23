package com.musinsapayments.point.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.musinsapayments.point.application.command.AccrualCommand;
import com.musinsapayments.point.config.PointProperties;
import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.domain.ledger.AccrualTransactionType;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.ledger.PointLedgerDetail;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PointAccrualServiceTest {

    private static final UUID REQUEST_ID = PointTestFixture.uuid(1);
    private static final Instant INSTANT = Instant.parse("2026-07-22T01:00:00Z");

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

    private PointAccrualService service;

    @BeforeEach
    void setUp() {
        service = new PointAccrualService(
                policies, ledgers, details, idempotency, keys, clock,
                new PointProperties(100_000L, 365, 7, ZoneId.of("Asia/Seoul")));
    }

    @Test
    void 일반_적립은_원장과_A에서_A로_향하는_상세을_만든다() {
        given(clock.instant()).willReturn(INSTANT);
        given(policies.findByCustomerIdForUpdate(100L)).willReturn(Optional.of(policy(10_000L)));
        given(ledgers.sumAvailableBalance(eq(100L), any())).willReturn(0L);
        given(keys.generate()).willReturn("A");

        PointMutationResult result = service.accrueNormal(
                new AccrualCommand(REQUEST_ID, 100L, 1_000L, null));

        assertThat(result.pointKey()).isEqualTo("A");
        assertThat(result.balanceAfter()).isEqualTo(1_000L);
        assertThat(result.expiresAt()).isEqualTo(OffsetDateTime.parse("2027-07-22T10:00:00+09:00"));
        assertThat(result.transactionDate()).isEqualTo(PointTestFixture.NOW.toLocalDate());
        then(details).should().save(org.mockito.ArgumentMatchers.argThat(detail ->
                detail.getPointKey().equals("A")
                        && detail.getSourceAccrualPointKey().equals("A")
                        && detail.getTargetAccrualPointKey().equals("A")));
        assertSavedTransactionType(AccrualTransactionType.NORMAL);
    }

    @Test
    void 관리자_수기_적립은_MANUAL_거래_타입으로_저장한다() {
        given(clock.instant()).willReturn(INSTANT);
        given(policies.findByCustomerIdForUpdate(100L)).willReturn(Optional.of(policy(10_000L)));
        given(ledgers.sumAvailableBalance(eq(100L), any())).willReturn(0L);
        given(keys.generate()).willReturn("M");

        service.accrueManual(new AccrualCommand(REQUEST_ID, 100L, 1_000L, 1));

        assertSavedTransactionType(AccrualTransactionType.MANUAL);
    }

    @Test
    void 적립_후_보유_한도를_넘으면_전체_실패한다() {
        given(clock.instant()).willReturn(INSTANT);
        given(policies.findByCustomerIdForUpdate(100L)).willReturn(Optional.of(policy(1_000L)));
        given(ledgers.sumAvailableBalance(eq(100L), any())).willReturn(900L);

        assertThatThrownBy(() -> service.accrueNormal(
                new AccrualCommand(REQUEST_ID, 100L, 101L, 365)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.HOLDING_LIMIT_EXCEEDED);
        then(ledgers).shouldHaveNoMoreInteractions();
        then(details).shouldHaveNoInteractions();
    }

    @Test
    void 하루_유효기간과_최대_금액을_적립할_수_있다() {
        given(clock.instant()).willReturn(INSTANT);
        given(policies.findByCustomerIdForUpdate(100L)).willReturn(Optional.of(policy(100_000L)));
        given(ledgers.sumAvailableBalance(eq(100L), any())).willReturn(0L);
        given(keys.generate()).willReturn("A");

        PointMutationResult result = service.accrueNormal(
                new AccrualCommand(REQUEST_ID, 100L, 100_000L, 1));

        assertThat(result.amount()).isEqualTo(100_000L);
        assertThat(result.expiresAt()).isEqualTo(OffsetDateTime.parse("2026-07-23T10:00:00+09:00"));
    }

    @Test
    void 유효기간이_정확히_5년이면_거절한다() {
        given(clock.instant()).willReturn(INSTANT);
        given(policies.findByCustomerIdForUpdate(100L)).willReturn(Optional.of(policy(10_000L)));

        assertThatThrownBy(() -> service.accrueNormal(
                new AccrualCommand(REQUEST_ID, 100L, 1L, 1_826)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.INVALID_REQUEST);
    }

    @Test
    void 유효기간이_5년보다_하루_짧으면_적립한다() {
        given(clock.instant()).willReturn(INSTANT);
        given(policies.findByCustomerIdForUpdate(100L)).willReturn(Optional.of(policy(10_000L)));
        given(ledgers.sumAvailableBalance(eq(100L), any())).willReturn(0L);
        given(keys.generate()).willReturn("A");

        PointMutationResult result = service.accrueNormal(
                new AccrualCommand(REQUEST_ID, 100L, 1L, 1_825));

        assertThat(result.expiresAt()).isEqualTo(OffsetDateTime.parse("2031-07-21T10:00:00+09:00"));
        ArgumentCaptor<PointLedger> ledgerCaptor = ArgumentCaptor.forClass(PointLedger.class);
        then(ledgers).should().save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getExpiresAt()).isEqualTo(result.expiresAt());
    }

    @Test
    void 같은_requestId의_정상_재생은_락을_얻지_않는다() {
        PointMutationResult replay = new PointMutationResult(
                "A", 100L, com.musinsapayments.point.domain.ledger.PointType.ACCRUAL,
                null, null, 1_000L, 1_000L, PointTestFixture.NOW,
                PointTestFixture.NOW.toLocalDate(), PointTestFixture.NOW.plusDays(365));
        given(idempotency.findAccrualReplay(
                REQUEST_ID, 100L, 1_000L, 365, AccrualTransactionType.NORMAL))
                .willReturn(Optional.of(replay));

        PointMutationResult result = service.accrueNormal(
                new AccrualCommand(REQUEST_ID, 100L, 1_000L, null));

        assertThat(result).isEqualTo(replay);
        then(policies).shouldHaveNoInteractions();
        then(ledgers).shouldHaveNoInteractions();
        then(details).shouldHaveNoInteractions();
    }

    @Test
    void 락_획득_후_재생이면_기존_결과만_반환한다() {
        PointMutationResult replay = new PointMutationResult(
                "A", 100L, com.musinsapayments.point.domain.ledger.PointType.ACCRUAL,
                null, null, 1_000L, 1_000L, PointTestFixture.NOW,
                PointTestFixture.NOW.toLocalDate(), PointTestFixture.NOW.plusDays(365));
        given(idempotency.findAccrualReplay(
                REQUEST_ID, 100L, 1_000L, 365, AccrualTransactionType.NORMAL))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(replay));
        given(policies.findByCustomerIdForUpdate(100L)).willReturn(Optional.of(policy(10_000L)));

        PointMutationResult result = service.accrueNormal(
                new AccrualCommand(REQUEST_ID, 100L, 1_000L, null));

        assertThat(result).isEqualTo(replay);
        then(policies).should().findByCustomerIdForUpdate(100L);
        then(clock).shouldHaveNoInteractions();
        then(ledgers).shouldHaveNoInteractions();
        then(details).shouldHaveNoInteractions();
    }

    @Test
    void 잔액_합계_덧셈이_넘치면_보유_한도_초과로_변환한다() {
        given(clock.instant()).willReturn(INSTANT);
        given(policies.findByCustomerIdForUpdate(100L)).willReturn(Optional.of(policy(Long.MAX_VALUE)));
        given(ledgers.sumAvailableBalance(eq(100L), any())).willReturn(Long.MAX_VALUE);

        assertThatThrownBy(() -> service.accrueNormal(
                new AccrualCommand(REQUEST_ID, 100L, 1L, 365)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.HOLDING_LIMIT_EXCEEDED);
        then(details).shouldHaveNoInteractions();
    }

    @Test
    void 정책이_없으면_정책_부재_오류를_반환한다() {
        assertThatThrownBy(() -> service.accrueNormal(
                new AccrualCommand(REQUEST_ID, 100L, 1L, 365)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.POLICY_NOT_FOUND);
        then(clock).shouldHaveNoInteractions();
    }

    @Test
    void 금액이_0이면_적립_금액_한도_오류를_반환한다() {
        assertThatThrownBy(() -> service.accrueNormal(
                new AccrualCommand(REQUEST_ID, 100L, 0L, 365)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.ACCRUAL_AMOUNT_LIMIT_EXCEEDED);
    }

    @Test
    void 금액이_최대값을_초과하면_적립_금액_한도_오류를_반환한다() {
        assertThatThrownBy(() -> service.accrueNormal(
                new AccrualCommand(REQUEST_ID, 100L, 100_001L, 365)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.ACCRUAL_AMOUNT_LIMIT_EXCEEDED);
    }

    @Test
    void 유효기간이_0이면_잘못된_요청_오류를_반환한다() {
        assertThatThrownBy(() -> service.accrueNormal(
                new AccrualCommand(REQUEST_ID, 100L, 1L, 0)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.INVALID_REQUEST);
    }

    private void assertSavedTransactionType(AccrualTransactionType expected) {
        ArgumentCaptor<PointLedger> ledgerCaptor = ArgumentCaptor.forClass(PointLedger.class);
        then(ledgers).should().save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getTransactionType()).isEqualTo(expected);
    }

    private CustomerPointPolicy policy(long holdingLimit) {
        return CustomerPointPolicy.create(100L, holdingLimit, PointTestFixture.NOW);
    }
}
