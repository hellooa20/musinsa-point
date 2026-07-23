package com.musinsapayments.point.application;

import com.musinsapayments.point.application.command.ChangePointPolicyCommand;
import com.musinsapayments.point.application.query.PointPolicyResult;
import com.musinsapayments.point.config.PointProperties;
import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.domain.policy.CustomerPointPolicy;
import com.musinsapayments.point.repository.CustomerPointPolicyRepository;
import com.musinsapayments.point.repository.PointLedgerRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PointPolicyService {

    private final CustomerPointPolicyRepository policies;
    private final PointLedgerRepository ledgers;
    private final Clock clock;
    private final PointProperties properties;

    public PointPolicyService(
            CustomerPointPolicyRepository policies, PointLedgerRepository ledgers,
            Clock clock, PointProperties properties) {
        this.policies = policies;
        this.ledgers = ledgers;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * 고객 포인트 정책 변경
     * @param command 고객 정보, 정책 정보
     * @return 정책 변경 결과
     */
    @Transactional
    public PointPolicyResult change(ChangePointPolicyCommand command) {

        // 고객 정책 조회 (락 처리 - 동시성)
        Optional<CustomerPointPolicy> locked = policies.findByCustomerIdForUpdate(command.customerId());
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), properties.zoneId());
        // 고객 정책 없다면 생성
        if (locked.isEmpty()) {
            CustomerPointPolicy created = CustomerPointPolicy.create(
                    command.customerId(), command.holdingLimit(), now);
            policies.save(created);
            return new PointPolicyResult(created.getCustomerId(), created.getHoldingLimit());
        }

        // 정책 변경 시 잔액 검증 -> 변경 처리
        CustomerPointPolicy policy = locked.get();
        long currentBalance = ledgers.sumAvailableBalance(command.customerId(), now);
        if (command.holdingLimit() < currentBalance) {
            throw new PointException(PointErrorCode.HOLDING_LIMIT_EXCEEDED);
        }
        policy.changeHoldingLimit(command.holdingLimit(), currentBalance, now);
        return new PointPolicyResult(policy.getCustomerId(), policy.getHoldingLimit());
    }
}
