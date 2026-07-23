package com.musinsapayments.point.support;

import com.musinsapayments.point.domain.ledger.AccrualTransactionType;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.ledger.PointLedgerDetail;
import com.musinsapayments.point.domain.policy.CustomerPointPolicy;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class PointTestFixture {

    public static final long CUSTOMER_ID = 100L;
    public static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-07-22T10:00:00+09:00");

    private PointTestFixture() {
    }

    public static UUID uuid(int value) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value));
    }

    public static CustomerPointPolicy policy(long holdingLimit) {
        return CustomerPointPolicy.create(CUSTOMER_ID, holdingLimit, NOW);
    }

    public static PointLedger accrual(
            String pointKey, AccrualTransactionType type, long amount,
            long remainingAmount, OffsetDateTime expiresAt) {
        PointLedger ledger = PointLedger.createAccrual(
                CUSTOMER_ID, pointKey,
                uuid(Math.toIntExact(Integer.toUnsignedLong(pointKey.hashCode()) % 1_000_000_000L) + 1).toString(),
                type, null, amount, amount, expiresAt, NOW, LocalDate.of(2026, 7, 22));
        if (remainingAmount < amount) {
            ledger.consume(amount - remainingAmount, expiresAt.minusNanos(1));
        }
        return ledger;
    }

    public static PointLedger use(String pointKey, long amount) {
        return PointLedger.createUse(
                CUSTOMER_ID, pointKey, uuid(Math.abs(pointKey.hashCode()) + 1).toString(),
                "ORDER-" + pointKey, amount, 0L, NOW, LocalDate.of(2026, 7, 22));
    }

    public static PointLedgerDetail detail(
            String ownerPointKey, String sourcePointKey, String targetPointKey,
            long amount, int sequenceNo) {
        return PointLedgerDetail.create(
                ownerPointKey, sourcePointKey, targetPointKey, amount, sequenceNo, NOW);
    }
}
