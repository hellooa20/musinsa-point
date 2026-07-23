package com.musinsapayments.point.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.musinsapayments.point.application.command.AccrualCancellationCommand;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PointAccrualCancellationIntegrationTest {

    @Autowired
    PointAccrualCancellationService service;

    @Autowired
    CustomerPointPolicyRepository policies;

    @Autowired
    PointLedgerRepository ledgers;

    @Autowired
    PointLedgerDetailRepository details;

    @Test
    void 전액_취소는_취소_원장과_A에서_null로_향하는_상세를_저장하고_재생한다() {
        createPolicy();
        PointLedger accrual = saveAccrual("A", 1_000L, 1_000L, OffsetDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(1));
        AccrualCancellationCommand command = command(1, "A");

        PointMutationResult first = service.cancel(command);
        PointMutationResult replay = service.cancel(command);

        PointLedger cancellation = ledgers.findByPointKey(first.pointKey()).orElseThrow();
        PointLedgerDetail detail = details.findByPointKeyOrderBySequenceNoAsc(first.pointKey()).getFirst();
        assertThat(replay).isEqualTo(first);
        assertThat(cancellation.getPointType()).isEqualTo(PointType.ACCRUAL_CANCEL);
        assertThat(cancellation.getReferencePointKey()).isEqualTo(accrual.getPointKey());
        assertThat(cancellation.getAmount()).isEqualTo(1_000L);
        assertThat(cancellation.getBalanceAfter()).isZero();
        assertThat(ledgers.findByPointKey("A").orElseThrow().getRemainingAmount()).isZero();
        assertThat(detail.getSourceAccrualPointKey()).isEqualTo("A");
        assertThat(detail.getTargetAccrualPointKey()).isNull();
        assertThat(ledgers.count()).isEqualTo(2L);
        assertThat(details.count()).isEqualTo(1L);
    }

    @Test
    void 부분_사용된_적립은_취소_원장이나_상세를_남기지_않는다() {
        createPolicy();
        saveAccrual("A", 1_000L, 999L, OffsetDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(1));

        assertThatThrownBy(() -> service.cancel(command(1, "A")))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.ACCRUAL_CANCEL_NOT_ALLOWED);

        assertThat(ledgers.findByPointKey("A").orElseThrow().getRemainingAmount()).isEqualTo(999L);
        assertThat(ledgers.count()).isEqualTo(1L);
        assertThat(details.count()).isZero();
    }

    @Test
    void 전액_사용_후_복원된_적립은_다시_전액_취소할_수_있다() {
        createPolicy();
        PointLedger accrual = saveAccrual("A", 1_000L, 0L, OffsetDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(1));
        accrual.restore(1_000L, OffsetDateTime.now(ZoneId.of("Asia/Seoul")));
        ledgers.saveAndFlush(accrual);

        PointMutationResult result = service.cancel(command(1, "A"));

        assertThat(result.amount()).isEqualTo(1_000L);
        assertThat(ledgers.findByPointKey("A").orElseThrow().getRemainingAmount()).isZero();
    }

    private PointLedger saveAccrual(
            String pointKey, long amount, long remainingAmount, OffsetDateTime expiresAt) {
        OffsetDateTime occurredAt = OffsetDateTime.now(ZoneId.of("Asia/Seoul"));
        PointLedger accrual = PointLedger.createAccrual(
                PointTestFixture.CUSTOMER_ID, pointKey, UUID.randomUUID().toString(),
                AccrualTransactionType.NORMAL, null, amount, amount, expiresAt,
                occurredAt, occurredAt.toLocalDate());
        if (remainingAmount < amount) {
            accrual.consume(amount - remainingAmount, occurredAt);
        }
        return ledgers.saveAndFlush(accrual);
    }

    private AccrualCancellationCommand command(int requestNumber, String accrualPointKey) {
        return new AccrualCancellationCommand(
                PointTestFixture.uuid(requestNumber), PointTestFixture.CUSTOMER_ID, accrualPointKey);
    }

    private void createPolicy() {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Seoul"));
        policies.saveAndFlush(CustomerPointPolicy.create(PointTestFixture.CUSTOMER_ID, 10_000L, now));
    }
}
