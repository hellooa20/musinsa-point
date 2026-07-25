package com.musinsapayments.point.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.musinsapayments.point.application.query.AccrualHistoryResult;
import com.musinsapayments.point.application.query.AccrualHistoryTransactionResult;
import com.musinsapayments.point.application.query.PageResult;
import com.musinsapayments.point.application.query.TransactionDetailResult;
import com.musinsapayments.point.application.query.TransactionSearchCondition;
import com.musinsapayments.point.application.query.TransactionSummaryResult;
import com.musinsapayments.point.domain.ledger.AccrualTransactionType;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.ledger.PointLedgerDetail;
import com.musinsapayments.point.domain.ledger.PointType;
import com.musinsapayments.point.domain.policy.CustomerPointPolicy;
import com.musinsapayments.point.repository.CustomerPointPolicyRepository;
import com.musinsapayments.point.repository.PointLedgerDetailRepository;
import com.musinsapayments.point.repository.PointLedgerRepository;
import com.musinsapayments.point.support.PointTestFixture;
import java.time.LocalDate;
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
class PointQueryIntegrationTest {

    @Autowired
    PointQueryService service;

    @Autowired
    CustomerPointPolicyRepository policies;

    @Autowired
    PointLedgerRepository ledgers;

    @Autowired
    PointLedgerDetailRepository details;

    @Test
    void 거래내역은_날짜_양끝을_포함해_최신순으로_반환하고_E와_빈페이지를_처리한다() {
        OffsetDateTime now = now();
        createPolicy(now);
        saveAccrual("A", 1_000L, now.minusDays(2));
        saveAccrual("B", 500L, now.minusDays(2));
        PointLedger use = saveUse("C", 400L, now.minusDays(1));
        PointLedger cancellation = saveUseCancellation("D", "C", 400L, now);
        PointLedger refund = ledgers.saveAndFlush(PointLedger.createExpiredUseRefund(
                PointTestFixture.CUSTOMER_ID, "E", "D", 400L, now.plusDays(7), now, now.toLocalDate()));
        details.saveAndFlush(PointLedgerDetail.create("C", "A", null, 400L, 1, now.minusDays(1)));
        details.saveAndFlush(PointLedgerDetail.create("D", "A", "E", 250L, 2, now));
        details.saveAndFlush(PointLedgerDetail.create("D", "B", null, 150L, 1, now));
        details.saveAndFlush(PointLedgerDetail.create("E", "E", "E", 400L, 1, now));

        PageResult<TransactionSummaryResult> result = service.transactions(new TransactionSearchCondition(
                PointTestFixture.CUSTOMER_ID, null, now.minusDays(1).toLocalDate(), now.toLocalDate(), 0, 20));
        PageResult<TransactionSummaryResult> empty = service.transactions(new TransactionSearchCondition(
                PointTestFixture.CUSTOMER_ID, null, now.plusDays(1).toLocalDate(), now.plusDays(1).toLocalDate(),
                0, 20));

        assertThat(result.content()).extracting(TransactionSummaryResult::pointKey).containsExactly("D", "C");
        assertThat(result.content()).noneMatch(item -> item.pointKey().equals(refund.getPointKey()));
        assertThat(empty.content()).isEmpty();
        assertThat(empty.totalElements()).isZero();

        TransactionDetailResult detail = service.transaction(cancellation.getPointKey());
        assertThat(detail.pointKey()).isEqualTo("D");
        assertThat(detail.details()).extracting(
                item -> item.sequenceNo(), item -> item.targetAccrualPointKey())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, null),
                        org.assertj.core.groups.Tuple.tuple(2, "E"));
        assertThat(use.getPointKey()).isEqualTo("C");
    }

    @Test
    void 같은_발생시각의_최상위_거래는_ID_내림차순으로_반환한다() {
        OffsetDateTime now = now();
        createPolicy(now);
        PointLedger first = saveUse("C", 100L, now);
        PointLedger second = saveUse("D", 200L, now);

        PageResult<TransactionSummaryResult> result = service.transactions(new TransactionSearchCondition(
                PointTestFixture.CUSTOMER_ID, PointType.USE, now.toLocalDate(), now.toLocalDate(), 0, 20));

        assertThat(second.getId()).isGreaterThan(first.getId());
        assertThat(result.content()).extracting(TransactionSummaryResult::pointKey).containsExactly("D", "C");
    }

    @Test
    void 적립이력은_전체_거래금액과_해당_적립의_배분금액을_함께_반환한다() {
        OffsetDateTime now = now();
        createPolicy(now);
        saveAccrual("A", 1_000L, now.minusDays(2));
        saveAccrual("B", 500L, now.minusDays(2));
        saveUse("C", 1_200L, now.minusDays(1));
        saveUseCancellation("D", "C", 1_100L, now);
        details.saveAndFlush(PointLedgerDetail.create("A", "A", "A", 1_000L, 1, now.minusDays(2)));
        details.saveAndFlush(PointLedgerDetail.create("B", "B", "B", 500L, 1, now.minusDays(2)));
        details.saveAndFlush(PointLedgerDetail.create("C", "A", null, 1_000L, 1, now.minusDays(1)));
        details.saveAndFlush(PointLedgerDetail.create("C", "B", null, 200L, 2, now.minusDays(1)));
        details.saveAndFlush(PointLedgerDetail.create("D", "A", "A", 900L, 1, now));
        details.saveAndFlush(PointLedgerDetail.create("D", "B", "B", 200L, 2, now));

        AccrualHistoryResult result = service.accrualHistory("A");

        assertThat(result.transactions()).extracting(item -> item.transaction().pointKey())
                .containsExactly("A", "C", "D");
        assertThat(result.transactions()).extracting(
                item -> item.transaction().amount(),
                AccrualHistoryTransactionResult::allocatedAmount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1_000L, 1_000L),
                        org.assertj.core.groups.Tuple.tuple(1_200L, 1_000L),
                        org.assertj.core.groups.Tuple.tuple(1_100L, 900L));
    }

    private void createPolicy(OffsetDateTime now) {
        policies.saveAndFlush(CustomerPointPolicy.create(PointTestFixture.CUSTOMER_ID, 10_000L, now));
    }

    private void saveAccrual(String pointKey, long amount, OffsetDateTime occurredAt) {
        ledgers.saveAndFlush(PointLedger.createAccrual(
                PointTestFixture.CUSTOMER_ID, pointKey, UUID.randomUUID().toString(),
                AccrualTransactionType.NORMAL, null, amount, amount, occurredAt.plusDays(30), occurredAt,
                occurredAt.toLocalDate()));
    }

    private PointLedger saveUse(String pointKey, long amount, OffsetDateTime occurredAt) {
        return ledgers.saveAndFlush(PointLedger.createUse(
                PointTestFixture.CUSTOMER_ID, pointKey, UUID.randomUUID().toString(), "ORDER-" + pointKey,
                amount, 600L, occurredAt, occurredAt.toLocalDate()));
    }

    private PointLedger saveUseCancellation(
            String pointKey, String usePointKey, long amount, OffsetDateTime occurredAt) {
        return ledgers.saveAndFlush(PointLedger.createUseCancellation(
                PointTestFixture.CUSTOMER_ID, pointKey, UUID.randomUUID().toString(), usePointKey,
                "ORDER-" + pointKey, amount, 1_000L, occurredAt, occurredAt.toLocalDate()));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Seoul")).withNano(0);
    }
}
