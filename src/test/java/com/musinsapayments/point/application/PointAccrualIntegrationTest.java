package com.musinsapayments.point.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.musinsapayments.point.application.command.AccrualCommand;
import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.domain.ledger.AccrualTransactionType;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.ledger.PointLedgerDetail;
import com.musinsapayments.point.domain.policy.CustomerPointPolicy;
import com.musinsapayments.point.repository.CustomerPointPolicyRepository;
import com.musinsapayments.point.repository.PointLedgerDetailRepository;
import com.musinsapayments.point.repository.PointLedgerRepository;
import com.musinsapayments.point.support.PointTestFixture;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PointAccrualIntegrationTest {

    @Autowired
    PointAccrualService service;

    @Autowired
    CustomerPointPolicyRepository policies;

    @Autowired
    PointLedgerRepository ledgers;

    @Autowired
    PointLedgerDetailRepository details;

    @Test
    void 일반과_수기_적립은_각각의_고정_거래_타입과_A에서_A로_향하는_상세를_저장한다() {
        createPolicy(200_000L);

        PointMutationResult normal = service.accrueNormal(command(1, 1_000L, null));
        PointMutationResult manual = service.accrueManual(command(2, 100_000L, 1));

        PointLedger normalLedger = ledgers.findByPointKey(normal.pointKey()).orElseThrow();
        PointLedger manualLedger = ledgers.findByPointKey(manual.pointKey()).orElseThrow();
        PointLedgerDetail normalDetail = details.findByPointKeyOrderBySequenceNoAsc(normal.pointKey()).getFirst();
        assertThat(normalLedger.getTransactionType()).isEqualTo(AccrualTransactionType.NORMAL);
        assertThat(manualLedger.getTransactionType()).isEqualTo(AccrualTransactionType.MANUAL);
        assertThat(normal.expiresAt()).isEqualTo(normal.occurredAt().plusDays(365));
        assertThat(manual.expiresAt()).isEqualTo(manual.occurredAt().plusDays(1));
        assertThat(manual.amount()).isEqualTo(100_000L);
        assertThat(normalDetail.getPointKey()).isEqualTo(normal.pointKey());
        assertThat(normalDetail.getSourceAccrualPointKey()).isEqualTo(normal.pointKey());
        assertThat(normalDetail.getTargetAccrualPointKey()).isEqualTo(normal.pointKey());
    }

    @Test
    void 보유_한도를_초과한_적립은_원장과_상세를_남기지_않는다() {
        createPolicy(1_000L);
        service.accrueNormal(command(1, 900L, 365));

        assertThatThrownBy(() -> service.accrueNormal(command(2, 101L, 365)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.HOLDING_LIMIT_EXCEEDED);

        assertThat(ledgers.count()).isEqualTo(1L);
        assertThat(details.count()).isEqualTo(1L);
    }

    @Test
    void 같은_requestId의_적립은_최초_결과를_재생하고_한_번만_저장한다() {
        createPolicy(10_000L);
        AccrualCommand command = command(1, 1_000L, 365);

        PointMutationResult first = service.accrueNormal(command);
        PointMutationResult replay = service.accrueNormal(command);

        assertThat(replay).isEqualTo(first);
        assertThat(ledgers.count()).isEqualTo(1L);
        assertThat(details.count()).isEqualTo(1L);
    }

    private AccrualCommand command(int requestNumber, long amount, Integer validityDays) {
        UUID requestId = PointTestFixture.uuid(requestNumber);
        return new AccrualCommand(requestId, PointTestFixture.CUSTOMER_ID, amount, validityDays);
    }

    private void createPolicy(long holdingLimit) {
        policies.save(CustomerPointPolicy.create(
                PointTestFixture.CUSTOMER_ID, holdingLimit, OffsetDateTime.now(ZoneOffset.UTC)));
    }
}
