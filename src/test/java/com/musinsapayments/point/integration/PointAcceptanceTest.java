package com.musinsapayments.point.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.musinsapayments.point.application.PointAccrualService;
import com.musinsapayments.point.application.PointMutationResult;
import com.musinsapayments.point.application.PointPolicyService;
import com.musinsapayments.point.application.PointQueryService;
import com.musinsapayments.point.application.PointUseCancellationService;
import com.musinsapayments.point.application.PointUseService;
import com.musinsapayments.point.application.command.AccrualCommand;
import com.musinsapayments.point.application.command.ChangePointPolicyCommand;
import com.musinsapayments.point.application.command.PointUseCancellationCommand;
import com.musinsapayments.point.application.command.PointUseCommand;
import com.musinsapayments.point.application.query.TransactionDetailResult;
import com.musinsapayments.point.domain.ledger.PointType;
import com.musinsapayments.point.repository.PointLedgerRepository;
import com.musinsapayments.point.support.MutableClock;
import com.musinsapayments.point.support.PointTestFixture;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(IntegrationTestClockConfig.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PointAcceptanceTest {

    @Autowired
    PointPolicyService policyService;

    @Autowired
    PointAccrualService accrualService;

    @Autowired
    PointUseService useService;

    @Autowired
    PointUseCancellationService cancellationService;

    @Autowired
    PointQueryService queryService;

    @Autowired
    PointLedgerRepository ledgers;

    @Autowired
    MutableClock clock;

    @Test
    void 과제의_혼합_적립_사용_부분취소_시나리오() {
        policyService.change(new ChangePointPolicyCommand(100L, 100_000L));
        PointMutationResult a = accrualService.accrueManual(
                new AccrualCommand(PointTestFixture.uuid(1), 100L, 1_000L, 1));
        PointMutationResult b = accrualService.accrueNormal(
                new AccrualCommand(PointTestFixture.uuid(2), 100L, 500L, 365));
        PointMutationResult c = useService.use(
                new PointUseCommand(PointTestFixture.uuid(3), 100L, "ORDER-A1234", 1_200L));

        clock.advance(Duration.ofDays(1));
        PointMutationResult d = cancellationService.cancel(
                new PointUseCancellationCommand(
                        PointTestFixture.uuid(4), 100L, c.pointKey(), "ORDER-A1234-CANCEL-1", 1_100L));

        assertThat(queryService.balance(100L).balance()).isEqualTo(1_400L);
        TransactionDetailResult detail = queryService.transaction(d.pointKey());
        assertThat(detail.details()).hasSize(2);
        assertThat(detail.details()).anySatisfy(it -> {
            assertThat(it.sourceAccrualPointKey()).isEqualTo(a.pointKey());
            assertThat(it.targetAccrualPointKey()).isNotEqualTo(a.pointKey());
            assertThat(it.amount()).isEqualTo(1_000L);
        });
        assertThat(detail.details()).anySatisfy(it -> {
            assertThat(it.sourceAccrualPointKey()).isEqualTo(b.pointKey());
            assertThat(it.targetAccrualPointKey()).isEqualTo(b.pointKey());
            assertThat(it.amount()).isEqualTo(100L);
        });
        assertThat(ledgers.sumAmountByReferencePointKeyAndPointType(
                c.pointKey(), PointType.USE_CANCEL)).isEqualTo(1_100L);
    }
}
