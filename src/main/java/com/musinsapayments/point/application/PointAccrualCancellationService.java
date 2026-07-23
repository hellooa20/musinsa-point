package com.musinsapayments.point.application;

import com.musinsapayments.point.application.command.AccrualCancellationCommand;
import com.musinsapayments.point.config.PointProperties;
import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.ledger.PointLedgerDetail;
import com.musinsapayments.point.domain.ledger.PointType;
import com.musinsapayments.point.repository.CustomerPointPolicyRepository;
import com.musinsapayments.point.repository.PointLedgerDetailRepository;
import com.musinsapayments.point.repository.PointLedgerRepository;
import com.musinsapayments.point.support.PointKeyGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PointAccrualCancellationService {

    private final CustomerPointPolicyRepository policies;
    private final PointLedgerRepository ledgers;
    private final PointLedgerDetailRepository details;
    private final PointIdempotencyGuard idempotency;
    private final PointKeyGenerator keys;
    private final Clock clock;
    private final PointProperties properties;

    public PointAccrualCancellationService(
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
     * 포인트 적립 취소
     * @param command 적립 취소 정보
     * @return 적립 취소 결과
     */
    @Transactional
    public PointMutationResult cancel(AccrualCancellationCommand command) {

        // 중복 요청 건 확인 (멱등성 검증)
        Optional<PointMutationResult> fastReplay = idempotency.findAccrualCancellationReplay(
                command.requestId(), command.customerId(), command.accrualPointKey());
        if (fastReplay.isPresent()) {
            return fastReplay.get();
        }

        // 락 걸기
        policies.findByCustomerIdForUpdate(command.customerId())
                .orElseThrow(() -> new PointException(PointErrorCode.POLICY_NOT_FOUND));
        Optional<PointMutationResult> lockedReplay = idempotency.findAccrualCancellationReplay(
                command.requestId(), command.customerId(), command.accrualPointKey());
        if (lockedReplay.isPresent()) {
            return lockedReplay.get();
        }

        // 유효한 적립 건인지 검증
        // 1. 적립 건인가
        // 2. 이미 취소된 건인가
        // 3. 만료된 건인가
        // 4. 잔액 == 적립금
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), properties.zoneId());
        PointLedger accrual = ledgers.findByPointKeyAndCustomerId(
                        command.accrualPointKey(), command.customerId())
                .filter(ledger -> ledger.getPointType() == PointType.ACCRUAL)
                .orElseThrow(() -> new PointException(PointErrorCode.POINT_NOT_FOUND));
        boolean alreadyCanceled = ledgers.existsByReferencePointKeyAndPointType(
                accrual.getPointKey(), PointType.ACCRUAL_CANCEL);
        if (alreadyCanceled || accrual.isExpired(now)
                || accrual.getRemainingAmount() != accrual.getAmount()) {
            throw new PointException(PointErrorCode.ACCRUAL_CANCEL_NOT_ALLOWED);
        }

        long currentBalance = ledgers.sumAvailableBalance(command.customerId(), now);
        if (currentBalance < accrual.getAmount()) {
            throw new PointException(PointErrorCode.ACCRUAL_CANCEL_NOT_ALLOWED);
        }

        // 적립 취소
        long balanceAfter = currentBalance - accrual.getAmount();
        String pointKey = keys.generate();
        PointLedger cancellation = PointLedger.createAccrualCancellation(
                command.customerId(), pointKey, command.requestId().toString(),
                accrual.getPointKey(), accrual.getAmount(), balanceAfter, now, now.toLocalDate());
        ledgers.save(cancellation);
        accrual.cancel(now);
        details.save(PointLedgerDetail.create(
                pointKey, accrual.getPointKey(), null, accrual.getAmount(), 1, now));
        return PointMutationResult.from(cancellation);
    }
}
