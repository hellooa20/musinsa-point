package com.musinsapayments.point.application;

import com.musinsapayments.point.application.query.AccrualHistoryResult;
import com.musinsapayments.point.application.query.AccrualHistoryTransactionResult;
import com.musinsapayments.point.application.query.LedgerDetailResult;
import com.musinsapayments.point.application.query.PageResult;
import com.musinsapayments.point.application.query.PointBalanceResult;
import com.musinsapayments.point.application.query.TransactionDetailResult;
import com.musinsapayments.point.application.query.TransactionSearchCondition;
import com.musinsapayments.point.application.query.TransactionSummaryResult;
import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.ledger.PointLedgerDetail;
import com.musinsapayments.point.domain.ledger.PointType;
import com.musinsapayments.point.domain.ledger.UseStatus;
import com.musinsapayments.point.repository.CustomerPointPolicyRepository;
import com.musinsapayments.point.repository.PointLedgerDetailRepository;
import com.musinsapayments.point.repository.PointLedgerRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PointQueryService {

    private final CustomerPointPolicyRepository policies;
    private final PointLedgerRepository ledgers;
    private final PointLedgerDetailRepository details;
    private final Clock clock;

    public PointQueryService(
            CustomerPointPolicyRepository policies, PointLedgerRepository ledgers,
            PointLedgerDetailRepository details, Clock clock) {
        this.policies = policies;
        this.ledgers = ledgers;
        this.details = details;
        this.clock = clock;
    }

    // 만료되지 않은 적립을 합산해 현재 잔액 조회
    @Transactional(readOnly = true)
    public PointBalanceResult balance(long customerId) {
        policies.findById(customerId)
                .orElseThrow(() -> new PointException(PointErrorCode.POLICY_NOT_FOUND));
        OffsetDateTime now = now();
        return new PointBalanceResult(customerId, ledgers.sumAvailableBalance(customerId, now), now);
    }

    // 조건에 맞는 최상위 거래 내역을 최신순으로 조회
    @Transactional(readOnly = true)
    public PageResult<TransactionSummaryResult> transactions(TransactionSearchCondition condition) {
        policies.findById(condition.customerId())
                .orElseThrow(() -> new PointException(PointErrorCode.POLICY_NOT_FOUND));

        Pageable pageable = PageRequest.of(condition.page(), condition.size(),
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));

        Page<PointLedger> page = ledgers.findTopLevelTransactions(
                condition.customerId(), condition.pointType(), condition.fromDate(), condition.toDate(), pageable);

        OffsetDateTime now = now();
        Map<String, String> statuses = dynamicStatuses(page.getContent(), now);
        List<TransactionSummaryResult> content = page.getContent().stream()
                .map(ledger -> toSummary(ledger, statuses.get(ledger.getPointKey())))
                .toList();
        return PageResult.from(page, content);
    }

    // pointKey에 해당하는 원장과 배분 상세 조회
    @Transactional(readOnly = true)
    public TransactionDetailResult transaction(String pointKey) {
        PointLedger ledger = ledgers.findByPointKey(pointKey)
                .orElseThrow(() -> new PointException(PointErrorCode.POINT_NOT_FOUND));

        List<LedgerDetailResult> detailResults = details.findByPointKeyOrderBySequenceNoAsc(pointKey).stream()
                .map(detail -> new LedgerDetailResult(
                        detail.getSourceAccrualPointKey(), detail.getTargetAccrualPointKey(),
                        detail.getAmount(), detail.getSequenceNo()))
                .toList();
        return TransactionDetailResult.from(ledger, dynamicStatus(ledger, now()), detailResults);
    }

    // 특정 적립을 직접 사용하거나 취소한 원장 이력 조회
    @Transactional(readOnly = true)
    public AccrualHistoryResult accrualHistory(String accrualPointKey) {
        PointLedger accrual = ledgers.findByPointKey(accrualPointKey)
                .filter(ledger -> ledger.getPointType() == PointType.ACCRUAL)
                .orElseThrow(() -> new PointException(PointErrorCode.POINT_NOT_FOUND));

        List<PointLedgerDetail> historyDetails =
                details.findBySourceAccrualPointKeyOrderByIdAsc(accrualPointKey);
        List<String> pointKeys = historyDetails.stream()
                .map(PointLedgerDetail::getPointKey)
                .distinct()
                .toList();
        Map<String, Long> allocatedAmountByPointKey = historyDetails.stream()
                .collect(Collectors.toMap(PointLedgerDetail::getPointKey, PointLedgerDetail::getAmount));

        Map<String, PointLedger> byKey = ledgers.findAllByPointKeyIn(pointKeys).stream()
                .collect(Collectors.toMap(PointLedger::getPointKey, Function.identity()));

        OffsetDateTime now = now();
        List<AccrualHistoryTransactionResult> history = pointKeys.stream()
                .map(byKey::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(PointLedger::getOccurredAt)
                        .thenComparing(PointLedger::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(ledger -> new AccrualHistoryTransactionResult(
                        toSummary(ledger, now),
                        allocatedAmountByPointKey.get(ledger.getPointKey())))
                .toList();
        return new AccrualHistoryResult(accrual.getPointKey(), history);
    }

    // 현재 시점과 취소 금액을 반영해 조회용 상태 계산
    private String dynamicStatus(PointLedger ledger, OffsetDateTime now) {
        if (ledger.getPointType() == PointType.ACCRUAL) {
            boolean canceled = ledgers.existsByReferencePointKeyAndPointType(
                    ledger.getPointKey(), PointType.ACCRUAL_CANCEL);
            return ledger.accrualStatus(now, canceled).name();
        }

        if (ledger.getPointType() == PointType.USE) {
            long canceled = ledgers.sumAmountByReferencePointKeyAndPointType(
                    ledger.getPointKey(), PointType.USE_CANCEL);
            return useStatus(ledger, canceled);
        }
        return ledger.getPointType().name();
    }

    //거래 목록의 취소 상태를 배치 조회하여 계산
    private Map<String, String> dynamicStatuses(
            List<PointLedger> transactions, OffsetDateTime now) {
        List<String> targetPointKeys = transactions.stream()
                .filter(ledger -> ledger.getPointType() == PointType.ACCRUAL
                        || ledger.getPointType() == PointType.USE)
                .map(PointLedger::getPointKey)
                .toList();
        if (targetPointKeys.isEmpty()) {
            return transactions.stream().collect(Collectors.toMap(
                    PointLedger::getPointKey, ledger -> ledger.getPointType().name()));
        }

        List<PointLedger> cancellations = ledgers.findByReferencePointKeyInAndPointTypeIn(
                targetPointKeys, List.of(PointType.ACCRUAL_CANCEL, PointType.USE_CANCEL));
        Set<String> canceledAccrualPointKeys = new HashSet<>();
        Map<String, Long> canceledUseAmounts = new HashMap<>();
        for (PointLedger cancellation : cancellations) {
            if (cancellation.getPointType() == PointType.ACCRUAL_CANCEL) {
                canceledAccrualPointKeys.add(cancellation.getReferencePointKey());
            } else if (cancellation.getPointType() == PointType.USE_CANCEL) {
                canceledUseAmounts.merge(
                        cancellation.getReferencePointKey(), cancellation.getAmount(), Math::addExact);
            }
        }

        Map<String, String> result = new HashMap<>();
        for (PointLedger ledger : transactions) {
            String status;
            if (ledger.getPointType() == PointType.ACCRUAL) {
                status = ledger.accrualStatus(
                        now, canceledAccrualPointKeys.contains(ledger.getPointKey())).name();
            } else if (ledger.getPointType() == PointType.USE) {
                status = useStatus(ledger, canceledUseAmounts.getOrDefault(ledger.getPointKey(), 0L));
            } else {
                status = ledger.getPointType().name();
            }
            result.put(ledger.getPointKey(), status);
        }
        return Map.copyOf(result);
    }

    private String useStatus(PointLedger ledger, long canceledAmount) {
        if (canceledAmount == 0) {
            return UseStatus.USED.name();
        }
        if (canceledAmount == ledger.getAmount()) {
            return UseStatus.FULLY_CANCELED.name();
        }
        return UseStatus.PARTIALLY_CANCELED.name();
    }

    // 원장을 거래 목록 응답 형태로 변환
    private TransactionSummaryResult toSummary(PointLedger ledger, OffsetDateTime now) {
        return toSummary(ledger, dynamicStatus(ledger, now));
    }

    private TransactionSummaryResult toSummary(PointLedger ledger, String status) {
        return new TransactionSummaryResult(
                ledger.getPointKey(), ledger.getCustomerId(), ledger.getPointType(),
                ledger.getTransactionType(), ledger.getReferencePointKey(), ledger.getOrderNumber(),
                ledger.getAmount(), ledger.getRemainingAmount(), ledger.getBalanceAfter(),
                status, ledger.getExpiresAt(), ledger.getOccurredAt(),
                ledger.getTransactionDate());
    }

    // 주입된 Clock 기준 현재 시각 반환
    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
