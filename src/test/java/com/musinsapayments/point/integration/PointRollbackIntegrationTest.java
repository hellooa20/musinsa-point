package com.musinsapayments.point.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.musinsapayments.point.application.PointAccrualService;
import com.musinsapayments.point.application.PointMutationResult;
import com.musinsapayments.point.application.PointPolicyService;
import com.musinsapayments.point.application.PointUseCancellationService;
import com.musinsapayments.point.application.PointUseService;
import com.musinsapayments.point.application.command.AccrualCommand;
import com.musinsapayments.point.application.command.ChangePointPolicyCommand;
import com.musinsapayments.point.application.command.PointUseCancellationCommand;
import com.musinsapayments.point.application.command.PointUseCommand;
import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.domain.ledger.PointLedgerDetail;
import com.musinsapayments.point.repository.PointLedgerDetailRepository;
import com.musinsapayments.point.repository.PointLedgerRepository;
import com.musinsapayments.point.support.PointTestFixture;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(IntegrationTestClockConfig.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PointRollbackIntegrationTest {

    @Autowired
    PointPolicyService policyService;

    @Autowired
    PointAccrualService accrualService;

    @Autowired
    PointUseService useService;

    @Autowired
    PointUseCancellationService cancellationService;

    @Autowired
    PointLedgerRepository ledgers;

    @MockitoSpyBean
    PointLedgerDetailRepository details;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 사용취소_보유한도_사전검증_실패는_기존_상태를_변경하지_않는다() {
        createPolicy(1_000L);
        PointMutationResult source = accrualService.accrueNormal(
                new AccrualCommand(PointTestFixture.uuid(1), 100L, 1_000L, 365));
        PointMutationResult use = useService.use(
                new PointUseCommand(PointTestFixture.uuid(2), 100L, "ORDER-1", 100L));
        long ledgerCountBefore = ledgers.count();
        long detailCountBefore = details.count();
        long remainingBefore = ledgers.findByPointKey(source.pointKey()).orElseThrow().getRemainingAmount();

        assertThatThrownBy(() -> cancellationService.cancel(new PointUseCancellationCommand(
                PointTestFixture.uuid(3), 100L, use.pointKey(), "ORDER-1-CANCEL", 101L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.HOLDING_LIMIT_EXCEEDED);

        assertUnchanged(ledgerCountBefore, detailCountBefore, source.pointKey(), remainingBefore);
    }

    @Test
    void 잔액부족_사용_사전검증_실패는_기존_상태를_변경하지_않는다() {
        createPolicy(10_000L);
        PointMutationResult source = accrualService.accrueNormal(
                new AccrualCommand(PointTestFixture.uuid(1), 100L, 500L, 365));
        long ledgerCountBefore = ledgers.count();
        long detailCountBefore = details.count();
        long remainingBefore = ledgers.findByPointKey(source.pointKey()).orElseThrow().getRemainingAmount();

        assertThatThrownBy(() -> useService.use(new PointUseCommand(
                PointTestFixture.uuid(2), 100L, "ORDER-1", 501L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.POINT_BALANCE_INSUFFICIENT);

        assertUnchanged(ledgerCountBefore, detailCountBefore, source.pointKey(), remainingBefore);
    }

    @Test
    void 사용취소_상세_제약_위반은_저장된_원장과_source_복원을_모두_롤백한다() {
        createPolicy(10_000L);
        PointMutationResult source = accrualService.accrueNormal(
                new AccrualCommand(PointTestFixture.uuid(1), 100L, 500L, 365));
        PointMutationResult use = useService.use(
                new PointUseCommand(PointTestFixture.uuid(2), 100L, "ORDER-1", 500L));
        long ledgerCountBefore = ledgers.count();
        long detailCountBefore = details.count();
        long remainingBefore = ledgers.findByPointKey(source.pointKey()).orElseThrow().getRemainingAmount();
        failNextDetailSaveWithDuplicateDatabaseRow();

        assertThatThrownBy(() -> cancellationService.cancel(new PointUseCancellationCommand(
                PointTestFixture.uuid(3), 100L, use.pointKey(), "ORDER-1-CANCEL", 500L)))
                .satisfies(error -> assertThat(rootCauseMessage(error))
                        .contains("uk_point_ledger_detail_source"));

        assertUnchanged(ledgerCountBefore, detailCountBefore, source.pointKey(), remainingBefore);
    }

    private void createPolicy(long holdingLimit) {
        policyService.change(new ChangePointPolicyCommand(100L, holdingLimit));
    }

    private void failNextDetailSaveWithDuplicateDatabaseRow() {
        doAnswer(invocation -> {
            Iterable<PointLedgerDetail> saved = invocation.getArgument(0);
            PointLedgerDetail detail = StreamSupport.stream(saved.spliterator(), false).findFirst().orElseThrow();
            entityManager.flush();
            jdbcTemplate.update("""
                    insert into point_ledger_detail (
                        point_key, source_accrual_point_key, target_accrual_point_key,
                        amount, sequence_no, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    detail.getPointKey(), detail.getSourceAccrualPointKey(), detail.getTargetAccrualPointKey(),
                    detail.getAmount(), detail.getSequenceNo() + 1, now(), now());
            entityManager.persist(detail);
            entityManager.flush();
            return List.of(detail);
        }).when(details).saveAll(any());
    }

    private void assertUnchanged(long ledgerCount, long detailCount, String sourcePointKey, long remainingAmount) {
        entityManager.clear();
        assertThat(ledgers.count()).isEqualTo(ledgerCount);
        assertThat(details.count()).isEqualTo(detailCount);
        assertThat(ledgers.findByPointKey(sourcePointKey).orElseThrow().getRemainingAmount())
                .isEqualTo(remainingAmount);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.parse("2026-07-22T10:00:00+09:00");
    }

    private String rootCauseMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage().toLowerCase(java.util.Locale.ROOT);
    }
}
