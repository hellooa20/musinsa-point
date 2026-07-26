package com.musinsapayments.point.application;

import com.musinsapayments.point.application.command.PointUseCancellationCommand;
import com.musinsapayments.point.config.PointProperties;
import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.ledger.PointLedgerDetail;
import com.musinsapayments.point.domain.ledger.PointType;
import com.musinsapayments.point.domain.policy.CustomerPointPolicy;
import com.musinsapayments.point.domain.use.PointCancellationAllocation;
import com.musinsapayments.point.domain.use.PointUseCancellationAllocator;
import com.musinsapayments.point.repository.CustomerPointPolicyRepository;
import com.musinsapayments.point.repository.PointLedgerDetailRepository;
import com.musinsapayments.point.repository.PointLedgerRepository;
import com.musinsapayments.point.support.PointKeyGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PointUseCancellationService {

    private final CustomerPointPolicyRepository policies;
    private final PointLedgerRepository ledgers;
    private final PointLedgerDetailRepository details;
    private final PointIdempotencyGuard idempotency;
    private final PointKeyGenerator keys;
    private final Clock clock;
    private final PointProperties properties;
    private final PointUseCancellationAllocator cancellationAllocator = new PointUseCancellationAllocator();

    public PointUseCancellationService(
            CustomerPointPolicyRepository policies, PointLedgerRepository ledgers,
            PointLedgerDetailRepository details, PointIdempotencyGuard idempotency,
            PointKeyGenerator keys, Clock clock, PointProperties properties) {
        this.policies = policies;
        this.ledgers = ledgers;
        this.details = details;
        this.idempotency = idempotency;
        this.keys = keys;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * 포인트 사용 취소
     * @param command 사용 취소 정보
     * @return 사용 취소 결과
     */
    @Transactional
    public PointMutationResult cancel(PointUseCancellationCommand command) {

        // 중복 요청 체크 (멱등성 검증)
        Optional<PointMutationResult> fastReplay = idempotency.findUseCancellationReplay(
                command.requestId(), command.customerId(), command.usePointKey(),
                command.cancelOrderNumber(), command.amount());
        if (fastReplay.isPresent()) {
            return fastReplay.get();
        }

        // 락 걸기
        CustomerPointPolicy policy = policies.findByCustomerIdForUpdate(command.customerId())
                .orElseThrow(() -> new PointException(PointErrorCode.POLICY_NOT_FOUND));

        // 중복 요청 재확인
        Optional<PointMutationResult> lockedReplay = idempotency.findUseCancellationReplay(
                command.requestId(), command.customerId(), command.usePointKey(),
                command.cancelOrderNumber(), command.amount());
        if (lockedReplay.isPresent()) {
            return lockedReplay.get();
        }

        // 주문 번호 중복 체크
        if (ledgers.findByOrderNumber(command.cancelOrderNumber()).isPresent()) {
            throw new PointException(PointErrorCode.ORDER_NUMBER_CONFLICT);
        }

        // 원 거래(사용) 건 조회
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), properties.zoneId());
        PointLedger originalUse = findOriginalUse(command);

        // 현재 잔액 조회
        long balanceAfter = calculateBalanceAfter(command, policy, now);

        // 원 거래(사용) 건의 상세 조회
        List<PointCancellationAllocation> allocations =
                allocateCancellation(originalUse, command.amount());
        Map<String, PointLedger> sourceAccruals =
                findSourceAccruals(allocations, command.customerId());

        // 사용 취소 처리 (원장)
        String cancellationPointKey = keys.generate();
        PointLedger cancellation = PointLedger.createUseCancellation(
                command.customerId(), cancellationPointKey, command.requestId().toString(),
                originalUse.getPointKey(), command.cancelOrderNumber(), command.amount(),
                balanceAfter, now, now.toLocalDate());
        ledgers.save(cancellation);

        // 사용 취소 처리 (원장 상세)
        List<PointLedgerDetail> newDetails = restorePoints(
                command.customerId(), cancellationPointKey, balanceAfter,
                allocations, sourceAccruals, now);
        details.saveAll(newDetails);
        return PointMutationResult.from(cancellation);
    }

    /**
     * 사용 취소 대상 원사용 원장을 조회
     * @param command 사용 취소 정보
     * @return 사용 원장
     */
    private PointLedger findOriginalUse(PointUseCancellationCommand command) {
        return ledgers.findByPointKeyAndCustomerId(command.usePointKey(), command.customerId())
                .filter(ledger -> ledger.getPointType() == PointType.USE)
                .orElseThrow(() -> new PointException(PointErrorCode.POINT_NOT_FOUND));
    }

    /**
     * 사용 취소 후 잔액을 계산하고 고객 보유 한도를 검증
     * @param command 사용 취소 정보
     * @param policy 고객 포인트 정책
     * @param now 처리 기준 시각
     * @return 사용 취소 후 잔액
     */
    private long calculateBalanceAfter(
            PointUseCancellationCommand command, CustomerPointPolicy policy, OffsetDateTime now) {
        long currentBalance = ledgers.sumAvailableBalance(command.customerId(), now);
        long balanceAfter;
        try {
            balanceAfter = Math.addExact(currentBalance, command.amount());
        } catch (ArithmeticException exception) {
            throw new PointException(PointErrorCode.HOLDING_LIMIT_EXCEEDED);
        }

        // 취소 요청 금액 검증 및 취소 후 고객 한도 초과 체크
        if (command.amount() <= 0 || balanceAfter > policy.getHoldingLimit()) {
            throw new PointException(PointErrorCode.HOLDING_LIMIT_EXCEEDED);
        }
        return balanceAfter;
    }

    /**
     * 원사용 상세와 누적 취소 금액을 기준으로 취소 요청 금액을 배분
     * @param originalUse 원사용 원장
     * @param amount 취소 요청 금액
     * @return 원적립별 취소 배분 결과
     */
    private List<PointCancellationAllocation> allocateCancellation(
            PointLedger originalUse, long amount) {
        List<PointLedgerDetail> originalDetails =
                details.findByPointKeyOrderBySequenceNoAsc(originalUse.getPointKey());
        Map<String, Long> canceledBySource =
                toCanceledMap(details.sumCanceledAmountBySource(originalUse.getPointKey()));
        long canceledLedgerAmount = ledgers.sumAmountByReferencePointKeyAndPointType(
                originalUse.getPointKey(), PointType.USE_CANCEL);
        validateCancellationHistory(
                originalUse, originalDetails, canceledBySource, canceledLedgerAmount, amount);
        try {
            // 원장 상세 건별 취소 금액 할당 처리
            return cancellationAllocator.allocate(
                    originalDetails, canceledBySource, amount);
        } catch (IllegalStateException exception) {
            throw new PointException(PointErrorCode.USE_CANCEL_AMOUNT_EXCEEDED);
        } catch (IllegalArgumentException exception) {
            throw new PointException(PointErrorCode.DATA_INTEGRITY_VIOLATION);
        }
    }

    /**
     * 취소 배분에 포함된 원적립 원장을 조회하고 유효성을 검증
     * @param allocations 원적립별 취소 배분 결과
     * @param customerId 고객 ID
     * @return pointKey를 기준으로 조회한 원적립 원장
     */
    private Map<String, PointLedger> findSourceAccruals(
            List<PointCancellationAllocation> allocations, long customerId) {
        Set<String> sourceKeys = allocations.stream()
                .map(PointCancellationAllocation::sourceAccrualPointKey)
                .collect(Collectors.toSet());

        Map<String, PointLedger> sourceAccruals = ledgers.findAllByPointKeyIn(sourceKeys).stream()
                .collect(Collectors.toMap(PointLedger::getPointKey, Function.identity()));

        validateSources(allocations, sourceAccruals, customerId);
        return sourceAccruals;
    }

    /**
     * 배분 결과에 따라 미만료 포인트를 복원하고 만료 포인트는 신규 적립으로 생성
     * @param customerId 고객 ID
     * @param cancellationPointKey 사용 취소 pointKey
     * @param balanceAfter 사용 취소 후 잔액
     * @param allocations 원적립별 취소 배분 결과
     * @param sourceAccruals pointKey별 원적립 원장
     * @param now 처리 기준 시각
     * @return 저장할 사용 취소 및 만료 재적립 상세
     */
    private List<PointLedgerDetail> restorePoints(
            long customerId, String cancellationPointKey, long balanceAfter,
            List<PointCancellationAllocation> allocations,
            Map<String, PointLedger> sourceAccruals, OffsetDateTime now) {

        List<PointLedgerDetail> newDetails = new ArrayList<>();

        for (PointCancellationAllocation allocation : allocations) {
            PointLedger source = sourceAccruals.get(allocation.sourceAccrualPointKey());

            // 사용 취소 되는 적립 건이 만료일 경우 -> 재적립 (유효기간 : 7일)
            if (source.isExpired(now)) {
                String refundPointKey = keys.generate();
                PointLedger refund = PointLedger.createExpiredUseRefund(
                        customerId, refundPointKey, cancellationPointKey,
                        allocation.amount(), balanceAfter,
                        now.plusDays(properties.expiredRefundValidityDays()),
                        now, now.toLocalDate());
                ledgers.save(refund);
                newDetails.add(PointLedgerDetail.create(
                        refundPointKey, refundPointKey, refundPointKey,
                        allocation.amount(), 1, now));
                newDetails.add(PointLedgerDetail.create(
                        cancellationPointKey, source.getPointKey(), refundPointKey,
                        allocation.amount(), allocation.sequenceNo(), now));
            } else {
                source.restore(allocation.amount(), now);
                newDetails.add(PointLedgerDetail.create(
                        cancellationPointKey, source.getPointKey(), source.getPointKey(),
                        allocation.amount(), allocation.sequenceNo(), now));
            }
        }
        return newDetails;
    }

    /**
     * 취소 대상 source가 모두 존재하는 적립 원장인지 검증
     * @param allocations 원적립별 취소 배분 결과
     * @param sourceAccruals pointKey별 원적립 원장
     * @param customerId 고객 ID
     */
    private void validateSources(
            List<PointCancellationAllocation> allocations,
            Map<String, PointLedger> sourceAccruals, long customerId) {
        for (PointCancellationAllocation allocation : allocations) {
            PointLedger source = sourceAccruals.get(allocation.sourceAccrualPointKey());
            if (source == null || source.getPointType() != PointType.ACCRUAL
                    || source.getCustomerId() != customerId) {
                throw new PointException(PointErrorCode.DATA_INTEGRITY_VIOLATION);
            }
        }
    }

    /**
     * 원사용·취소 원장과 상세 금액이 서로 일치하고 추가 취소 가능한지 검증
     * @param originalUse 원사용 원장
     * @param originalDetails 원사용 상세
     * @param canceledBySource source별 누적 취소 금액
     * @param canceledLedgerAmount 사용 취소 원장 누적 금액
     * @param requestedAmount 취소 요청 금액
     */
    private void validateCancellationHistory(
            PointLedger originalUse, List<PointLedgerDetail> originalDetails,
            Map<String, Long> canceledBySource, long canceledLedgerAmount,
            long requestedAmount) {
        long originalDetailAmount;
        long canceledDetailAmount;
        try {
            originalDetailAmount = originalDetails.stream()
                    .mapToLong(PointLedgerDetail::getAmount)
                    .reduce(0L, Math::addExact);
            canceledDetailAmount = canceledBySource.values().stream()
                    .mapToLong(Long::longValue)
                    .reduce(0L, Math::addExact);
        } catch (ArithmeticException exception) {
            throw new PointException(PointErrorCode.DATA_INTEGRITY_VIOLATION);
        }

        if (originalDetailAmount != originalUse.getAmount()
                || canceledDetailAmount != canceledLedgerAmount
                || canceledLedgerAmount < 0
                || canceledLedgerAmount > originalUse.getAmount()) {
            throw new PointException(PointErrorCode.DATA_INTEGRITY_VIOLATION);
        }
        if (requestedAmount > originalUse.getAmount() - canceledLedgerAmount) {
            throw new PointException(PointErrorCode.USE_CANCEL_AMOUNT_EXCEEDED);
        }
    }

    /**
     * source별 누적 취소 금액 조회 결과를 배분 계산용 Map으로 변환
     * @param rows source pointKey와 누적 취소 금액 조회 결과
     * @return source pointKey별 누적 취소 금액
     */
    private Map<String, Long> toCanceledMap(List<Object[]> rows) {
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put((String) row[0], ((Number) row[1]).longValue());
        }
        return Map.copyOf(result);
    }
}
