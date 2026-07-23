package com.musinsapayments.point.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.musinsapayments.point.application.command.PointUseCancellationCommand;
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
import com.musinsapayments.point.support.PointTestFixture;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PointUseCancellationIntegrationTest {

    @Autowired
    PointUseCancellationService service;

    @Autowired
    CustomerPointPolicyRepository policies;

    @Autowired
    PointLedgerRepository ledgers;

    @Autowired
    PointLedgerDetailRepository details;

    @Test
    void 만료_source는_7일_E로_재적립하고_미만료_source는_직접_복원한다() {
        createPolicy(10_000L);
        saveAccrual("A", AccrualTransactionType.MANUAL, 1_000L, 0L, now().minusNanos(1));
        saveAccrual("B", AccrualTransactionType.NORMAL, 500L, 300L, now().plusDays(1));
        saveUseWithDetails("C", 1_200L, List.of(
                PointTestFixture.detail("C", "A", null, 1_000L, 1),
                PointTestFixture.detail("C", "B", null, 200L, 2)));

        PointMutationResult result = service.cancel(command(1, "C", "CANCEL-1", 1_100L));

        PointLedger cancellation = ledgers.findByPointKey(result.pointKey()).orElseThrow();
        PointLedger refund = ledgers.findByReferencePointKeyAndPointType(
                cancellation.getPointKey(), PointType.ACCRUAL).getFirst();
        assertThat(result.balanceAfter()).isEqualTo(1_400L);
        assertThat(ledgers.findByPointKey("A").orElseThrow().getRemainingAmount()).isZero();
        assertThat(ledgers.findByPointKey("B").orElseThrow().getRemainingAmount()).isEqualTo(400L);
        assertThat(refund.getTransactionType()).isEqualTo(AccrualTransactionType.EXPIRED_USE_REFUND);
        assertThat(refund.getAmount()).isEqualTo(1_000L);
        assertThat(refund.getBalanceAfter()).isNull();
        assertThat(refund.getExpiresAt()).isEqualTo(refund.getOccurredAt().plusDays(7));
        assertThat(details.findByPointKeyOrderBySequenceNoAsc(cancellation.getPointKey())).extracting(
                PointLedgerDetail::getSourceAccrualPointKey,
                PointLedgerDetail::getTargetAccrualPointKey,
                PointLedgerDetail::getAmount,
                PointLedgerDetail::getSequenceNo).containsExactly(
                tuple("A", refund.getPointKey(), 1_000L, 1), tuple("B", "B", 100L, 2));
        assertThat(details.findByPointKeyOrderBySequenceNoAsc(refund.getPointKey())).extracting(
                PointLedgerDetail::getSourceAccrualPointKey,
                PointLedgerDetail::getTargetAccrualPointKey,
                PointLedgerDetail::getAmount,
                PointLedgerDetail::getSequenceNo).containsExactly(
                tuple(refund.getPointKey(), refund.getPointKey(), 1_000L, 1));
    }

    @Test
    void 부분_반복_후_전액_취소는_FIFO_누적_한도를_적용한다() {
        createPolicy(10_000L);
        saveAccrual("A", AccrualTransactionType.MANUAL, 1_000L, 0L, now().plusDays(1));
        saveAccrual("B", AccrualTransactionType.NORMAL, 500L, 300L, now().plusDays(1));
        saveUseWithDetails("C", 1_200L, List.of(
                PointTestFixture.detail("C", "A", null, 1_000L, 1),
                PointTestFixture.detail("C", "B", null, 200L, 2)));

        service.cancel(command(1, "C", "CANCEL-1", 400L));
        PointMutationResult full = service.cancel(command(2, "C", "CANCEL-2", 800L));

        assertThat(full.balanceAfter()).isEqualTo(1_500L);
        assertThat(ledgers.findByPointKey("A").orElseThrow().getRemainingAmount()).isEqualTo(1_000L);
        assertThat(ledgers.findByPointKey("B").orElseThrow().getRemainingAmount()).isEqualTo(500L);
        assertThat(details.findByPointKeyOrderBySequenceNoAsc(full.pointKey())).extracting(
                PointLedgerDetail::getSourceAccrualPointKey,
                PointLedgerDetail::getTargetAccrualPointKey,
                PointLedgerDetail::getAmount,
                PointLedgerDetail::getSequenceNo).containsExactly(
                tuple("A", "A", 600L, 1), tuple("B", "B", 200L, 2));
        assertThatThrownBy(() -> service.cancel(command(3, "C", "CANCEL-3", 1L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.USE_CANCEL_AMOUNT_EXCEEDED);
    }

    @Test
    void 보유_한도_초과는_D_저장과_원본_복원을_모두_롤백한다() {
        createPolicy(1_000L);
        saveAccrual("A", AccrualTransactionType.NORMAL, 1_000L, 900L, now().plusDays(1));
        saveUseWithDetails("C", 101L, List.of(PointTestFixture.detail("C", "A", null, 101L, 1)));

        assertThatThrownBy(() -> service.cancel(command(1, "C", "CANCEL-1", 101L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.HOLDING_LIMIT_EXCEEDED);

        assertThat(ledgers.count()).isEqualTo(2L);
        assertThat(details.count()).isEqualTo(1L);
        assertThat(ledgers.findByPointKey("A").orElseThrow().getRemainingAmount()).isEqualTo(900L);
    }

    @Test
    void source가_적립이_아니면_D_저장까지_트랜잭션으로_롤백한다() {
        createPolicy(10_000L);
        saveUse("MISSING", 100L);
        saveUseWithDetails("C", 100L, List.of(PointTestFixture.detail("C", "MISSING", null, 100L, 1)));

        assertThatThrownBy(() -> service.cancel(command(1, "C", "CANCEL-1", 100L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.DATA_INTEGRITY_VIOLATION);

        assertThat(ledgers.count()).isEqualTo(2L);
        assertThat(details.count()).isEqualTo(1L);
    }

    private PointUseCancellationCommand command(
            int requestNumber, String usePointKey, String cancelOrderNumber, long amount) {
        return new PointUseCancellationCommand(
                PointTestFixture.uuid(requestNumber), PointTestFixture.CUSTOMER_ID,
                usePointKey, cancelOrderNumber, amount);
    }

    private void createPolicy(long holdingLimit) {
        policies.saveAndFlush(CustomerPointPolicy.create(PointTestFixture.CUSTOMER_ID, holdingLimit, now()));
    }

    private void saveUseWithDetails(String pointKey, long amount, List<PointLedgerDetail> originalDetails) {
        saveUse(pointKey, amount);
        details.saveAllAndFlush(originalDetails);
    }

    private void saveUse(String pointKey, long amount) {
        OffsetDateTime occurredAt = now();
        ledgers.saveAndFlush(PointLedger.createUse(
                PointTestFixture.CUSTOMER_ID, pointKey, UUID.randomUUID().toString(),
                "ORDER-" + pointKey, amount, 0L, occurredAt, occurredAt.toLocalDate()));
    }

    private void saveAccrual(
            String pointKey, AccrualTransactionType transactionType, long amount,
            long remainingAmount, OffsetDateTime expiresAt) {
        OffsetDateTime occurredAt = now();
        PointLedger accrual = PointLedger.createAccrual(
                PointTestFixture.CUSTOMER_ID, pointKey, UUID.randomUUID().toString(), transactionType,
                null, amount, amount, expiresAt, occurredAt, occurredAt.toLocalDate());
        if (remainingAmount < amount) {
            accrual.consume(amount - remainingAmount,
                    expiresAt.isBefore(occurredAt) ? expiresAt.minusNanos(1) : occurredAt);
        }
        ledgers.saveAndFlush(accrual);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
