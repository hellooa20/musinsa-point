package com.musinsapayments.point.domain.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "customer_point_policy")
public class CustomerPointPolicy {

    @Id
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "holding_limit", nullable = false)
    private long holdingLimit;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected CustomerPointPolicy() {
    }

    private CustomerPointPolicy(Long customerId, long holdingLimit, OffsetDateTime now) {
        if (customerId == null || customerId <= 0 || holdingLimit < 0) {
            throw new IllegalArgumentException("고객 ID와 보유 한도를 확인해 주세요.");
        }
        this.customerId = customerId;
        this.holdingLimit = holdingLimit;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static CustomerPointPolicy create(Long customerId, long holdingLimit, OffsetDateTime now) {
        return new CustomerPointPolicy(customerId, holdingLimit, now);
    }

    public void changeHoldingLimit(long newLimit, long currentBalance, OffsetDateTime now) {
        if (newLimit < currentBalance) {
            throw new IllegalArgumentException("보유 한도는 현재 잔액보다 작을 수 없습니다.");
        }
        holdingLimit = newLimit;
        updatedAt = now;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public long getHoldingLimit() {
        return holdingLimit;
    }
}
