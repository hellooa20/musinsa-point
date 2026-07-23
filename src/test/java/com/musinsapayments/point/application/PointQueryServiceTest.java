package com.musinsapayments.point.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;

import com.musinsapayments.point.application.query.AccrualHistoryResult;
import com.musinsapayments.point.application.query.PageResult;
import com.musinsapayments.point.application.query.PointBalanceResult;
import com.musinsapayments.point.application.query.TransactionDetailResult;
import com.musinsapayments.point.application.query.TransactionSearchCondition;
import com.musinsapayments.point.application.query.TransactionSummaryResult;
import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.domain.ledger.AccrualTransactionType;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.ledger.PointLedgerDetail;
import com.musinsapayments.point.domain.ledger.PointType;
import com.musinsapayments.point.repository.CustomerPointPolicyRepository;
import com.musinsapayments.point.repository.PointLedgerDetailRepository;
import com.musinsapayments.point.repository.PointLedgerRepository;
import com.musinsapayments.point.support.PointTestFixture;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class PointQueryServiceTest {

    private static final long CUSTOMER_ID = 100L;
    private static final OffsetDateTime NOW = PointTestFixture.NOW;

    @Mock
    CustomerPointPolicyRepository policies;

    @Mock
    PointLedgerRepository ledgers;

    @Mock
    PointLedgerDetailRepository details;

    private PointQueryService service;

    @BeforeEach
    void setUp() {
        service = new PointQueryService(policies, ledgers, details,
                Clock.fixed(NOW.toInstant(), NOW.getOffset()));
    }

    @Test
    void 현재_잔액은_정책을_확인하고_계산_시각과_함께_반환한다() {
        given(policies.findById(CUSTOMER_ID)).willReturn(Optional.of(PointTestFixture.policy(10_000L)));
        given(ledgers.sumAvailableBalance(CUSTOMER_ID, NOW)).willReturn(1_400L);

        PointBalanceResult result = service.balance(CUSTOMER_ID);

        assertThat(result).isEqualTo(new PointBalanceResult(CUSTOMER_ID, 1_400L, NOW));
    }

    @Test
    void 거래내역은_내부_재적립_E를_숨기고_balanceAfter를_과거_스냅샷으로_표시한다() {
        PointLedger useCancellationD = PointLedger.createUseCancellation(
                CUSTOMER_ID, "D", PointTestFixture.uuid(4).toString(), "C", "ORDER-CANCEL",
                1_000L, 1_400L, NOW, NOW.toLocalDate());
        given(policies.findById(CUSTOMER_ID)).willReturn(Optional.of(PointTestFixture.policy(10_000L)));
        given(ledgers.findTopLevelTransactions(eq(CUSTOMER_ID), isNull(), isNull(), isNull(), any()))
                .willReturn(new PageImpl<>(List.of(useCancellationD), PageRequest.of(0, 20), 1));

        PageResult<TransactionSummaryResult> result = service.transactions(
                new TransactionSearchCondition(CUSTOMER_ID, null, null, null, 0, 20));

        assertThat(result.content()).extracting(TransactionSummaryResult::pointKey)
                .containsExactly("D");
        assertThat(result.content().getFirst().balanceAfter()).isEqualTo(1_400L);
    }

    @Test
    void 거래상세는_원장상세를_순번순으로_표시하고_적립취소상태를_우선한다() {
        PointLedger accrual = PointTestFixture.accrual(
                "A", AccrualTransactionType.NORMAL, 1_000L, 1_000L, NOW.minusDays(1));
        PointLedgerDetail second = PointTestFixture.detail("A", "A", "A", 400L, 2);
        PointLedgerDetail first = PointTestFixture.detail("A", "A", "A", 600L, 1);
        given(ledgers.findByPointKey("A")).willReturn(Optional.of(accrual));
        given(ledgers.existsByReferencePointKeyAndPointType("A", PointType.ACCRUAL_CANCEL))
                .willReturn(true);
        given(details.findByPointKeyOrderBySequenceNoAsc("A")).willReturn(List.of(first, second));

        TransactionDetailResult result = service.transaction("A");

        assertThat(result.status()).isEqualTo("CANCELED");
        assertThat(result.details()).extracting(detail -> detail.sequenceNo())
                .containsExactly(1, 2);
    }

    @Test
    void 사용상태는_취소누적액에_따라_부분취소와_전액취소를_구분한다() {
        PointLedger use = PointTestFixture.use("C", 1_000L);
        given(ledgers.findByPointKey("C")).willReturn(Optional.of(use));
        given(details.findByPointKeyOrderBySequenceNoAsc("C")).willReturn(List.of());
        given(ledgers.sumAmountByReferencePointKeyAndPointType("C", PointType.USE_CANCEL))
                .willReturn(400L, 1_000L);

        assertThat(service.transaction("C").status()).isEqualTo("PARTIALLY_CANCELED");
        assertThat(service.transaction("C").status()).isEqualTo("FULLY_CANCELED");
    }

    @Test
    void A의_직접_이력은_A_C_D만_오래된_순으로_반환한다() {
        PointLedger ledgerA = PointTestFixture.accrual(
                "A", AccrualTransactionType.NORMAL, 1_000L, 0L, NOW.plusDays(1));
        PointLedger ledgerC = PointLedger.createUse(
                CUSTOMER_ID, "C", PointTestFixture.uuid(3).toString(), "ORDER-1",
                1_000L, 0L, NOW.plusMinutes(1), NOW.toLocalDate());
        PointLedger ledgerD = PointLedger.createUseCancellation(
                CUSTOMER_ID, "D", PointTestFixture.uuid(4).toString(), "C", "ORDER-1-CANCEL",
                1_000L, 1_000L, NOW.plusMinutes(2), NOW.toLocalDate());
        given(ledgers.findByPointKey("A")).willReturn(Optional.of(ledgerA));
        given(details.findBySourceAccrualPointKeyOrderByIdAsc("A"))
                .willReturn(List.of(
                        PointTestFixture.detail("A", "A", "A", 1_000L, 1),
                        PointTestFixture.detail("C", "A", null, 1_000L, 1),
                        PointTestFixture.detail("D", "A", "E", 1_000L, 1)));
        given(ledgers.findAllByPointKeyIn(List.of("A", "C", "D")))
                .willReturn(List.of(ledgerD, ledgerA, ledgerC));
        given(ledgers.existsByReferencePointKeyAndPointType("A", PointType.ACCRUAL_CANCEL))
                .willReturn(false);
        given(ledgers.sumAmountByReferencePointKeyAndPointType("C", PointType.USE_CANCEL))
                .willReturn(1_000L);

        AccrualHistoryResult result = service.accrualHistory("A");

        assertThat(result.transactions()).extracting(TransactionSummaryResult::pointKey)
                .containsExactly("A", "C", "D");
    }

    @Test
    void 없는_정책은_잔액_조회에서_정책없음_오류를_반환한다() {
        given(policies.findById(CUSTOMER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.balance(CUSTOMER_ID))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.POLICY_NOT_FOUND);
    }

    @Test
    void 조회조건은_유효하지_않은_페이징과_역전된_기간을_거절한다() {
        assertThatThrownBy(() -> new TransactionSearchCondition(0L, null, null, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransactionSearchCondition(CUSTOMER_ID, null, null, null, -1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransactionSearchCondition(CUSTOMER_ID, null, null, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransactionSearchCondition(CUSTOMER_ID, null, null, null, 0, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransactionSearchCondition(
                CUSTOMER_ID, null, LocalDate.of(2026, 7, 23), LocalDate.of(2026, 7, 22), 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
