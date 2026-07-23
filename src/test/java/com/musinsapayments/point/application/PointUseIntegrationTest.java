package com.musinsapayments.point.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.musinsapayments.point.application.command.PointUseCommand;
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
class PointUseIntegrationTest {

    @Autowired
    PointUseService service;

    @Autowired
    CustomerPointPolicyRepository policies;

    @Autowired
    PointLedgerRepository ledgers;

    @Autowired
    PointLedgerDetailRepository details;

    @Test
    void 수기_적립을_우선해_복수_적립에서_사용_원장과_상세를_커밋한다() {
        createPolicy();
        saveAccrual("A", AccrualTransactionType.MANUAL, 1_000L, now().plusDays(30));
        saveAccrual("B", AccrualTransactionType.NORMAL, 500L, now().plusDays(1));

        PointMutationResult result = service.use(command(1, "ORDER-1", 1_200L));

        PointLedger use = ledgers.findByPointKey(result.pointKey()).orElseThrow();
        List<PointLedgerDetail> savedDetails = details.findByPointKeyOrderBySequenceNoAsc(result.pointKey());
        assertThat(use.getPointType()).isEqualTo(PointType.USE);
        assertThat(use.getAmount()).isEqualTo(1_200L);
        assertThat(use.getBalanceAfter()).isEqualTo(300L);
        assertThat(use.getTransactionDate()).isEqualTo(use.getOccurredAt().toLocalDate());
        assertThat(savedDetails).extracting(
                PointLedgerDetail::getSourceAccrualPointKey,
                PointLedgerDetail::getTargetAccrualPointKey,
                PointLedgerDetail::getAmount,
                PointLedgerDetail::getSequenceNo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("A", null, 1_000L, 1),
                        org.assertj.core.groups.Tuple.tuple("B", null, 200L, 2));
        assertThat(savedDetails.stream().mapToLong(PointLedgerDetail::getAmount).sum()).isEqualTo(1_200L);
        assertThat(ledgers.findByPointKey("A").orElseThrow().getRemainingAmount()).isZero();
        assertThat(ledgers.findByPointKey("B").orElseThrow().getRemainingAmount()).isEqualTo(300L);
    }

    @Test
    void 동일한_만료시각과_발생시각의_일반_적립은_ID순으로_배분한다() {
        createPolicy();
        OffsetDateTime occurredAt = now();
        OffsetDateTime expiresAt = occurredAt.plusDays(1);
        PointLedger accrualA = saveAccrual(
                "A", AccrualTransactionType.NORMAL, 500L, expiresAt, occurredAt);
        PointLedger accrualB = saveAccrual(
                "B", AccrualTransactionType.NORMAL, 500L, expiresAt, occurredAt);

        PointMutationResult result = service.use(command(1, "ORDER-1", 1_000L));

        List<PointLedgerDetail> savedDetails = details.findByPointKeyOrderBySequenceNoAsc(result.pointKey());
        assertThat(accrualA.getId()).isLessThan(accrualB.getId());
        assertThat(savedDetails).extracting(
                PointLedgerDetail::getSourceAccrualPointKey,
                PointLedgerDetail::getSequenceNo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("A", 1),
                        org.assertj.core.groups.Tuple.tuple("B", 2));
        assertThat(savedDetails.stream().mapToLong(PointLedgerDetail::getAmount).sum()).isEqualTo(1_000L);
    }

    @Test
    void 잔액이_부족한_사용은_원장과_상세와_원본_적립을_변경하지_않는다() {
        createPolicy();
        saveAccrual("A", AccrualTransactionType.NORMAL, 500L, now().plusDays(1));

        assertThatThrownBy(() -> service.use(command(1, "ORDER-1", 501L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.POINT_BALANCE_INSUFFICIENT);

        assertThat(ledgers.count()).isEqualTo(1L);
        assertThat(details.count()).isZero();
        assertThat(ledgers.findByPointKey("A").orElseThrow().getRemainingAmount()).isEqualTo(500L);
    }

    @Test
    void 동일_요청은_재생하고_다른_요청의_같은_주문번호는_거절한다() {
        createPolicy();
        saveAccrual("A", AccrualTransactionType.NORMAL, 1_000L, now().plusDays(1));
        PointUseCommand command = command(1, "ORDER-1", 1_000L);

        PointMutationResult first = service.use(command);
        PointMutationResult replay = service.use(command);

        assertThat(replay).isEqualTo(first);
        assertThatThrownBy(() -> service.use(command(2, "ORDER-1", 1L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.ORDER_NUMBER_CONFLICT);
        assertThat(ledgers.count()).isEqualTo(2L);
        assertThat(details.count()).isEqualTo(1L);
        assertThat(ledgers.findByPointKey("A").orElseThrow().getRemainingAmount()).isZero();
    }

    private PointUseCommand command(int requestNumber, String orderNumber, long amount) {
        return new PointUseCommand(
                PointTestFixture.uuid(requestNumber), PointTestFixture.CUSTOMER_ID, orderNumber, amount);
    }

    private void createPolicy() {
        policies.saveAndFlush(CustomerPointPolicy.create(
                PointTestFixture.CUSTOMER_ID, 10_000L, now()));
    }

    private void saveAccrual(
            String pointKey, AccrualTransactionType transactionType, long amount, OffsetDateTime expiresAt) {
        OffsetDateTime occurredAt = now();
        saveAccrual(pointKey, transactionType, amount, expiresAt, occurredAt);
    }

    private PointLedger saveAccrual(
            String pointKey, AccrualTransactionType transactionType, long amount,
            OffsetDateTime expiresAt, OffsetDateTime occurredAt) {
        return ledgers.saveAndFlush(PointLedger.createAccrual(
                PointTestFixture.CUSTOMER_ID, pointKey, UUID.randomUUID().toString(),
                transactionType, null, amount, amount, expiresAt, occurredAt, occurredAt.toLocalDate()));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
