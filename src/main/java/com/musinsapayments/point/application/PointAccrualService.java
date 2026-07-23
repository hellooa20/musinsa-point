package com.musinsapayments.point.application;

import com.musinsapayments.point.application.command.AccrualCommand;
import com.musinsapayments.point.config.PointProperties;
import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.domain.ledger.AccrualTransactionType;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.ledger.PointLedgerDetail;
import com.musinsapayments.point.domain.policy.CustomerPointPolicy;
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
public class PointAccrualService {

    private final CustomerPointPolicyRepository policies;
    private final PointLedgerRepository ledgers;
    private final PointLedgerDetailRepository details;
    private final PointIdempotencyGuard idempotency;
    private final PointKeyGenerator keys;
    private final Clock clock;
    private final PointProperties properties;

    public PointAccrualService(
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
     * 포인트 일반 적립
     * @param command 적립 정보
     * @return 적립 결과
     */
    @Transactional
    public PointMutationResult accrueNormal(AccrualCommand command) {
        return accrue(command, AccrualTransactionType.NORMAL);
    }

    /**
     * 포인트 관리자 수기 적립
     * @param command 적립 정보
     * @return 적립 결과
     */
    @Transactional
    public PointMutationResult accrueManual(AccrualCommand command) {
        return accrue(command, AccrualTransactionType.MANUAL);
    }

    /**
     * 포인트 적립 처리
     * @param command 적립 정보
     * @param transactionType 적립 타입 (일반 적립, 수기 적립)
     * @return 적립 결과
     */
    private PointMutationResult accrue(
            AccrualCommand command, AccrualTransactionType transactionType) {

        // 유효기간
        int validityDays = command.normalizedValidityDays(properties.defaultValidityDays());
        validateAmountAndValidity(command.amount(), validityDays);

        // 중복 요청 건인지 확인 (멱등성 검증)
        Optional<PointMutationResult> fastReplay = idempotency.findAccrualReplay(
                command.requestId(), command.customerId(), command.amount(), validityDays, transactionType);
        if (fastReplay.isPresent()) {
            return fastReplay.get();
        }

        // 락 걸기
        CustomerPointPolicy policy = policies.findByCustomerIdForUpdate(command.customerId())
                .orElseThrow(() -> new PointException(PointErrorCode.POLICY_NOT_FOUND));

        // 락 걸고 한번 더 중복 요청 건 체크
        Optional<PointMutationResult> lockedReplay = idempotency.findAccrualReplay(
                command.requestId(), command.customerId(), command.amount(), validityDays, transactionType);
        if (lockedReplay.isPresent()) {
            return lockedReplay.get();
        }

        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), properties.zoneId());
        OffsetDateTime expiresAt = now.plusDays(validityDays);
        if (!expiresAt.isBefore(now.plusYears(5))) {
            throw new PointException(PointErrorCode.INVALID_REQUEST);
        }

        long currentBalance = ledgers.sumAvailableBalance(command.customerId(), now);
        long balanceAfter;
        try {
            balanceAfter = Math.addExact(currentBalance, command.amount());
        } catch (ArithmeticException exception) {
            throw new PointException(PointErrorCode.HOLDING_LIMIT_EXCEEDED);
        }
        if (balanceAfter > policy.getHoldingLimit()) {
            throw new PointException(PointErrorCode.HOLDING_LIMIT_EXCEEDED);
        }

        // 적립 (원장, 원장 상세 둘 다 저장)
        String pointKey = keys.generate();
        PointLedger ledger = PointLedger.createAccrual(
                command.customerId(), pointKey, command.requestId().toString(), transactionType,
                null, command.amount(), balanceAfter, expiresAt, now, now.toLocalDate());
        ledgers.save(ledger);
        details.save(PointLedgerDetail.create(
                pointKey, pointKey, pointKey, command.amount(), 1, now));

        return PointMutationResult.from(ledger);
    }

    private void validateAmountAndValidity(long amount, int validityDays) {
        if (amount < 1 || amount > properties.maxAccrualAmount()) {
            throw new PointException(PointErrorCode.ACCRUAL_AMOUNT_LIMIT_EXCEEDED);
        }
        if (validityDays < 1) {
            throw new PointException(PointErrorCode.INVALID_REQUEST);
        }
    }
}
