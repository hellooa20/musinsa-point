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

        // 중복 요청 체크 (멱등성 검중)
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
        PointLedger originalUse = ledgers.findByPointKeyAndCustomerId(
                        command.usePointKey(), command.customerId())
                .filter(ledger -> ledger.getPointType() == PointType.USE)
                .orElseThrow(() -> new PointException(PointErrorCode.POINT_NOT_FOUND));

        // 현재 잔액 조회
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

        // 원 거래(사용) 건의 상세 조회
        List<PointLedgerDetail> originalDetails =
                details.findByPointKeyOrderBySequenceNoAsc(originalUse.getPointKey());
        List<PointCancellationAllocation> allocations;
        try {
            // 원장 상세 건별 취소 금액 할당 처리
            allocations = cancellationAllocator.allocate(
                    originalDetails,
                    toCanceledMap(details.sumCanceledAmountBySource(originalUse.getPointKey())),
                    command.amount());
        } catch (IllegalStateException exception) {
            throw new PointException(PointErrorCode.USE_CANCEL_AMOUNT_EXCEEDED);
        } catch (IllegalArgumentException exception) {
            throw new PointException(PointErrorCode.DATA_INTEGRITY_VIOLATION);
        }

        // 사용 취소 처리 (원장)
        String cancellationPointKey = keys.generate();
        PointLedger cancellation = PointLedger.createUseCancellation(
                command.customerId(), cancellationPointKey, command.requestId().toString(),
                originalUse.getPointKey(), command.cancelOrderNumber(), command.amount(),
                balanceAfter, now, now.toLocalDate());
        ledgers.save(cancellation);

        // 사용 취소 처리 (원장 상세)
        Set<String> sourceKeys = allocations.stream()
                .map(PointCancellationAllocation::sourceAccrualPointKey)
                .collect(Collectors.toSet());
        Map<String, PointLedger> sourceAccruals = ledgers.findAllByPointKeyIn(sourceKeys).stream()
                .collect(Collectors.toMap(PointLedger::getPointKey, Function.identity()));
        validateSources(allocations, sourceAccruals);

        List<PointLedgerDetail> newDetails = new ArrayList<>();
        for (PointCancellationAllocation allocation : allocations) {
            PointLedger source = sourceAccruals.get(allocation.sourceAccrualPointKey());
            // 사용 취소 되는 적립 건이 만료일 경우 -> 재적립 (유효기간 : 7일)
            if (source.isExpired(now)) {
                String refundPointKey = keys.generate();
                PointLedger refund = PointLedger.createExpiredUseRefund(
                        command.customerId(), refundPointKey, cancellationPointKey,
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
        details.saveAll(newDetails);
        return PointMutationResult.from(cancellation);
    }

    private void validateSources(
            List<PointCancellationAllocation> allocations, Map<String, PointLedger> sourceAccruals) {
        for (PointCancellationAllocation allocation : allocations) {
            PointLedger source = sourceAccruals.get(allocation.sourceAccrualPointKey());
            if (source == null || source.getPointType() != PointType.ACCRUAL) {
                throw new PointException(PointErrorCode.DATA_INTEGRITY_VIOLATION);
            }
        }
    }

    private Map<String, Long> toCanceledMap(List<Object[]> rows) {
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put((String) row[0], ((Number) row[1]).longValue());
        }
        return Map.copyOf(result);
    }
}
