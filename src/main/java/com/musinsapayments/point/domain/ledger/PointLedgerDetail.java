package com.musinsapayments.point.domain.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "point_ledger_detail")
public class PointLedgerDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "point_key", nullable = false)
    private String pointKey;

    @Column(name = "source_accrual_point_key", nullable = false)
    private String sourceAccrualPointKey;

    @Column(name = "target_accrual_point_key")
    private String targetAccrualPointKey;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PointLedgerDetail() {
    }

    public static PointLedgerDetail create(
            String pointKey, String sourceAccrualPointKey,
            String targetAccrualPointKey, long amount, int sequenceNo,
            OffsetDateTime now) {

        if (pointKey == null || sourceAccrualPointKey == null || amount <= 0 || sequenceNo <= 0) {
            throw new IllegalArgumentException("원장 상세 필수값을 확인해 주세요.");
        }

        PointLedgerDetail detail = new PointLedgerDetail();
        detail.pointKey = pointKey;
        detail.sourceAccrualPointKey = sourceAccrualPointKey;
        detail.targetAccrualPointKey = targetAccrualPointKey;
        detail.amount = amount;
        detail.sequenceNo = sequenceNo;
        detail.createdAt = now;
        detail.updatedAt = now;
        return detail;
    }

    public Long getId() {
        return id;
    }

    public String getPointKey() {
        return pointKey;
    }

    public String getSourceAccrualPointKey() {
        return sourceAccrualPointKey;
    }

    public String getTargetAccrualPointKey() {
        return targetAccrualPointKey;
    }

    public long getAmount() {
        return amount;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }
}
