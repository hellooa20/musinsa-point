package com.musinsapayments.point.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.musinsapayments.point.application.PointAccrualCancellationService;
import com.musinsapayments.point.application.PointAccrualService;
import com.musinsapayments.point.application.PointMutationResult;
import com.musinsapayments.point.application.PointPolicyService;
import com.musinsapayments.point.application.PointUseCancellationService;
import com.musinsapayments.point.application.PointUseService;
import com.musinsapayments.point.application.command.AccrualCancellationCommand;
import com.musinsapayments.point.application.command.AccrualCommand;
import com.musinsapayments.point.application.command.ChangePointPolicyCommand;
import com.musinsapayments.point.application.command.PointUseCancellationCommand;
import com.musinsapayments.point.application.command.PointUseCommand;
import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.repository.PointLedgerRepository;
import com.musinsapayments.point.support.PointTestFixture;
import java.util.UUID;
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
class PointIdempotencyIntegrationTest {

    @Autowired
    PointPolicyService policyService;

    @Autowired
    PointAccrualService accrualService;

    @Autowired
    PointAccrualCancellationService accrualCancellationService;

    @Autowired
    PointUseService useService;

    @Autowired
    PointUseCancellationService useCancellationService;

    @Autowired
    PointLedgerRepository ledgers;

    @Test
    void 적립을_사용한_뒤_같은_적립_requestId를_보내도_최초_응답이다() {
        createPolicy();
        UUID requestId = PointTestFixture.uuid(1);
        AccrualCommand command = new AccrualCommand(requestId, 100L, 1_000L, 365);
        PointMutationResult first = accrualService.accrueNormal(command);
        useService.use(new PointUseCommand(PointTestFixture.uuid(2), 100L, "ORDER-1", 500L));

        PointMutationResult replay = accrualService.accrueNormal(command);

        assertThat(replay).isEqualTo(first);
        assertThat(ledgers.findByRequestId(requestId.toString())).isPresent();
        assertRequestIdConflict(() -> accrualService.accrueNormal(
                new AccrualCommand(requestId, 100L, 999L, 365)));
    }

    @Test
    void 사용취소_뒤에도_이전_사용_requestId는_최초_응답이다() {
        createPolicy();
        accrualService.accrueNormal(new AccrualCommand(PointTestFixture.uuid(1), 100L, 1_000L, 365));
        UUID requestId = PointTestFixture.uuid(2);
        PointUseCommand command = new PointUseCommand(requestId, 100L, "ORDER-1", 500L);
        PointMutationResult first = useService.use(command);
        useCancellationService.cancel(new PointUseCancellationCommand(
                PointTestFixture.uuid(3), 100L, first.pointKey(), "ORDER-1-CANCEL", 500L));

        assertThat(useService.use(command)).isEqualTo(first);
        assertRequestIdConflict(() -> useService.use(
                new PointUseCommand(requestId, 100L, "ORDER-2", 500L)));
    }

    @Test
    void 적립취소는_같은_입력을_재생하고_다른_입력을_거절한다() {
        createPolicy();
        PointMutationResult accrual = accrualService.accrueNormal(
                new AccrualCommand(PointTestFixture.uuid(1), 100L, 1_000L, 365));
        UUID requestId = PointTestFixture.uuid(2);
        AccrualCancellationCommand command = new AccrualCancellationCommand(requestId, 100L, accrual.pointKey());
        PointMutationResult first = accrualCancellationService.cancel(command);

        assertThat(accrualCancellationService.cancel(command)).isEqualTo(first);
        assertRequestIdConflict(() -> accrualCancellationService.cancel(
                new AccrualCancellationCommand(requestId, 100L, "OTHER-ACCRUAL")));
    }

    @Test
    void 후속_부분취소_뒤에도_이전_사용취소_requestId는_최초_응답이다() {
        createPolicy();
        accrualService.accrueNormal(new AccrualCommand(PointTestFixture.uuid(1), 100L, 1_000L, 365));
        PointMutationResult use = useService.use(
                new PointUseCommand(PointTestFixture.uuid(2), 100L, "ORDER-1", 1_000L));
        UUID requestId = PointTestFixture.uuid(3);
        PointUseCancellationCommand firstCommand = new PointUseCancellationCommand(
                requestId, 100L, use.pointKey(), "ORDER-1-CANCEL-1", 400L);
        PointMutationResult first = useCancellationService.cancel(firstCommand);
        useCancellationService.cancel(new PointUseCancellationCommand(
                PointTestFixture.uuid(4), 100L, use.pointKey(), "ORDER-1-CANCEL-2", 300L));

        assertThat(useCancellationService.cancel(firstCommand)).isEqualTo(first);
        assertRequestIdConflict(() -> useCancellationService.cancel(new PointUseCancellationCommand(
                requestId, 100L, use.pointKey(), "ORDER-1-CANCEL-1", 401L)));
    }

    private void createPolicy() {
        policyService.change(new ChangePointPolicyCommand(100L, 10_000L));
    }

    private void assertRequestIdConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.REQUEST_ID_CONFLICT);
    }
}
