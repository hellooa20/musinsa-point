package com.musinsapayments.point.domain.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "point_ledger")
public class PointLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "point_key", nullable = false)
    private String pointKey;

    @Column(name = "request_id")
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "point_type", nullable = false)
    private PointType pointType;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type")
    private AccrualTransactionType transactionType;

    @Column(name = "reference_point_key")
    private String referencePointKey;

    @Column(name = "order_number")
    private String orderNumber;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "remaining_amount")
    private Long remainingAmount;

    @Column(name = "balance_after")
    private Long balanceAfter;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PointLedger() {
    }

    // 적립 건 생성
    public static PointLedger createAccrual(
            Long customerId, String pointKey, String requestId,
            AccrualTransactionType transactionType, String referencePointKey,
            long amount, long balanceAfter, OffsetDateTime expiresAt,
            OffsetDateTime occurredAt, LocalDate transactionDate) {

        if (transactionType == null || expiresAt == null || amount <= 0) {
            throw new IllegalArgumentException("적립 필수값을 확인해 주세요.");
        }

        if (transactionType == AccrualTransactionType.EXPIRED_USE_REFUND) {
            throw new IllegalArgumentException("만료 재적립은 전용 팩토리로 생성해 주세요.");
        }

        if (referencePointKey != null) {
            throw new IllegalArgumentException("일반 적립은 원본 포인트를 참조할 수 없습니다.");
        }

        PointLedger ledger = base(customerId, pointKey, requireText(requestId, "requestId"), PointType.ACCRUAL,
                amount, balanceAfter, occurredAt, transactionDate);
        ledger.transactionType = transactionType;
        ledger.referencePointKey = referencePointKey;
        ledger.remainingAmount = amount;
        ledger.expiresAt = expiresAt;
        return ledger;
    }

    // 적립 취소 건 생성
    public static PointLedger createAccrualCancellation(
            Long customerId, String pointKey, String requestId, String accrualPointKey,
            long amount, long balanceAfter, OffsetDateTime occurredAt, LocalDate transactionDate) {
        PointLedger ledger = base(customerId, pointKey, requireText(requestId, "requestId"), PointType.ACCRUAL_CANCEL,
                amount, balanceAfter, occurredAt, transactionDate);
        ledger.referencePointKey = requireText(accrualPointKey, "원본 적립 pointKey");
        return ledger;
    }

    // 사용 건 생성
    public static PointLedger createUse(
            Long customerId, String pointKey, String requestId, String orderNumber,
            long amount, long balanceAfter, OffsetDateTime occurredAt, LocalDate transactionDate) {
        PointLedger ledger = base(customerId, pointKey, requireText(requestId, "requestId"), PointType.USE,
                amount, balanceAfter, occurredAt, transactionDate);
        ledger.orderNumber = requireText(orderNumber, "주문번호");
        return ledger;
    }

    // 사용 취소 건 생성
    public static PointLedger createUseCancellation(
            Long customerId, String pointKey, String requestId, String usePointKey,
            String cancelOrderNumber, long amount, long balanceAfter,
            OffsetDateTime occurredAt, LocalDate transactionDate) {
        PointLedger ledger = base(customerId, pointKey, requireText(requestId, "requestId"), PointType.USE_CANCEL,
                amount, balanceAfter, occurredAt, transactionDate);
        ledger.referencePointKey = requireText(usePointKey, "원본 사용 pointKey");
        ledger.orderNumber = requireText(cancelOrderNumber, "취소 주문번호");
        return ledger;
    }

    // 사용 취소 만료 재적립 건 생성
    public static PointLedger createExpiredUseRefund(
            Long customerId, String pointKey, String useCancellationPointKey,
            long amount, OffsetDateTime expiresAt, OffsetDateTime occurredAt,
            LocalDate transactionDate) {

        if (expiresAt == null || amount <= 0) {
            throw new IllegalArgumentException("적립 필수값을 확인해 주세요.");
        }

        PointLedger ledger = base(customerId, pointKey, null, PointType.ACCRUAL,
                amount, 0L, occurredAt, transactionDate);
        ledger.transactionType = AccrualTransactionType.EXPIRED_USE_REFUND;
        ledger.referencePointKey = requireText(useCancellationPointKey, "원본 사용취소 pointKey");
        ledger.remainingAmount = amount;
        ledger.expiresAt = expiresAt;
        return ledger.withoutBalanceSnapshot();
    }

    public void consume(long amount, OffsetDateTime now) {
        requireAccrual();
        if (isExpired(now) || amount <= 0 || remainingAmount < amount) {
            throw new IllegalStateException("사용할 수 없는 적립입니다.");
        }
        remainingAmount -= amount;
        updatedAt = now;
    }

    public void restore(long amount, OffsetDateTime now) {
        requireAccrual();
        long restored = Math.addExact(remainingAmount, amount);
        if (amount <= 0 || restored > this.amount) {
            throw new IllegalStateException("복원 금액이 원본 적립을 초과합니다.");
        }
        remainingAmount = restored;
        updatedAt = now;
    }

    public void cancel(OffsetDateTime now) {
        requireAccrual();
        if (isExpired(now) || remainingAmount != amount) {
            throw new IllegalStateException("적립을 취소할 수 없습니다.");
        }
        remainingAmount = 0L;
        updatedAt = now;
    }

    public boolean isExpired(OffsetDateTime now) {
        requireAccrual();
        return !now.isBefore(expiresAt);
    }

    public AccrualStatus accrualStatus(OffsetDateTime now, boolean canceled) {
        requireAccrual();
        if (canceled) {
            return AccrualStatus.CANCELED;
        }
        if (isExpired(now)) {
            return AccrualStatus.EXPIRED;
        }
        if (remainingAmount == 0) {
            return AccrualStatus.EXHAUSTED;
        }
        if (remainingAmount < amount) {
            return AccrualStatus.PARTIALLY_AVAILABLE;
        }
        return AccrualStatus.AVAILABLE;
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getPointKey() {
        return pointKey;
    }

    public String getRequestId() {
        return requestId;
    }

    public PointType getPointType() {
        return pointType;
    }

    public AccrualTransactionType getTransactionType() {
        return transactionType;
    }

    public String getReferencePointKey() {
        return referencePointKey;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public long getAmount() {
        return amount;
    }

    public Long getRemainingAmount() {
        return remainingAmount;
    }

    public Long getBalanceAfter() {
        return balanceAfter;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public LocalDate getTransactionDate() {
        return transactionDate;
    }

    private static PointLedger base(
            Long customerId, String pointKey, String requestId, PointType pointType,
            long amount, long balanceAfter, OffsetDateTime occurredAt, LocalDate transactionDate) {
        if (customerId == null || customerId <= 0 || amount <= 0 || balanceAfter < 0
                || occurredAt == null || transactionDate == null) {
            throw new IllegalArgumentException("원장 필수값을 확인해 주세요.");
        }
        PointLedger ledger = new PointLedger();
        ledger.customerId = customerId;
        ledger.pointKey = requireText(pointKey, "pointKey");
        ledger.requestId = requestId;
        ledger.pointType = pointType;
        ledger.amount = amount;
        ledger.balanceAfter = balanceAfter;
        ledger.occurredAt = occurredAt;
        ledger.transactionDate = transactionDate;
        ledger.createdAt = occurredAt;
        ledger.updatedAt = occurredAt;
        return ledger;
    }

    private PointLedger withoutBalanceSnapshot() {
        balanceAfter = null;
        return this;
    }

    private void requireAccrual() {
        if (pointType != PointType.ACCRUAL) {
            throw new IllegalStateException("적립 원장만 변경할 수 있습니다.");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "을(를) 확인해 주세요.");
        }
        return value;
    }
}
