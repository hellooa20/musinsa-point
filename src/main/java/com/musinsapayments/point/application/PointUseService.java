package com.musinsapayments.point.application;

import com.musinsapayments.point.application.command.PointUseCommand;
import com.musinsapayments.point.config.PointProperties;
import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.ledger.PointLedgerDetail;
import com.musinsapayments.point.domain.use.PointAllocation;
import com.musinsapayments.point.domain.use.PointUseAllocator;
import com.musinsapayments.point.repository.CustomerPointPolicyRepository;
import com.musinsapayments.point.repository.PointLedgerDetailRepository;
import com.musinsapayments.point.repository.PointLedgerRepository;
import com.musinsapayments.point.support.PointKeyGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PointUseService {

    private final CustomerPointPolicyRepository policies;
    private final PointLedgerRepository ledgers;
    private final PointLedgerDetailRepository details;
    private final PointIdempotencyGuard idempotency;
    private final PointKeyGenerator keys;
    private final Clock clock;
    private final PointProperties properties;
    private final PointUseAllocator allocator = new PointUseAllocator();

    public PointUseService(
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
     * 포인트 사용
     * @param command 사용 정보
     * @return 사용 결과
     */
    @Transactional
    public PointMutationResult use(PointUseCommand command) {

        // 중복 요청 건인지 확인 (멱등성 검증)
        Optional<PointMutationResult> fastReplay = idempotency.findUseReplay(
                command.requestId(), command.customerId(), command.orderNumber(), command.amount());
        if (fastReplay.isPresent()) {
            return fastReplay.get();
        }

        // 락 걸기
        policies.findByCustomerIdForUpdate(command.customerId())
                .orElseThrow(() -> new PointException(PointErrorCode.POLICY_NOT_FOUND));

        // 중복 요청 재확인
        Optional<PointMutationResult> lockedReplay = idempotency.findUseReplay(
                command.requestId(), command.customerId(), command.orderNumber(), command.amount());
        if (lockedReplay.isPresent()) {
            return lockedReplay.get();
        }

        // 주문 번호 중복 체크
        if (ledgers.findByOrderNumber(command.orderNumber()).isPresent()) {
            throw new PointException(PointErrorCode.ORDER_NUMBER_CONFLICT);
        }

        // 현재 잔액 >= 사용 포인트 체크
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), properties.zoneId());
        long currentBalance = ledgers.sumAvailableBalance(command.customerId(), now);
        if (command.amount() <= 0 || currentBalance < command.amount()) {
            throw new PointException(PointErrorCode.POINT_BALANCE_INSUFFICIENT);
        }

        // 사용 가능한 포인트 조회
        List<PointLedger> candidates = ledgers.findSpendableAccruals(command.customerId(), now);
        List<PointAllocation> allocations;
        try {
            allocations = allocator.allocate(candidates, command.amount(), now);
        } catch (IllegalStateException exception) {
            throw new PointException(PointErrorCode.POINT_BALANCE_INSUFFICIENT);
        }

        // 사용 처리 (원장)
        String pointKey = keys.generate();
        PointLedger use = PointLedger.createUse(
                command.customerId(), pointKey, command.requestId().toString(),
                command.orderNumber(), command.amount(), currentBalance - command.amount(),
                now, now.toLocalDate());
        ledgers.save(use);

        // 사용 처리 (원장 상세)
        Map<String, PointLedger> byKey = candidates.stream()
                .collect(Collectors.toMap(PointLedger::getPointKey, Function.identity()));
        List<PointLedgerDetail> useDetails = new ArrayList<>();
        for (PointAllocation allocation : allocations) {
            PointLedger accrual = byKey.get(allocation.accrualPointKey());
            accrual.consume(allocation.amount(), now);
            useDetails.add(PointLedgerDetail.create(
                    pointKey, allocation.accrualPointKey(), null,
                    allocation.amount(), allocation.sequenceNo(), now));
        }
        details.saveAll(useDetails);
        return PointMutationResult.from(use);
    }
}
