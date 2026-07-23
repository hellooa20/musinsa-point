package com.musinsapayments.point.repository;

import static com.musinsapayments.point.domain.ledger.AccrualTransactionType.MANUAL;
import static com.musinsapayments.point.domain.ledger.AccrualTransactionType.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;

import com.musinsapayments.point.config.PointTimeConfig;
import com.musinsapayments.point.domain.ledger.AccrualTransactionType;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.policy.CustomerPointPolicy;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@Import(PointTimeConfig.class)
@ActiveProfiles("test")
class PointRepositoryTest {

    @Autowired
    CustomerPointPolicyRepository policies;

    @Autowired
    PointLedgerRepository ledgers;

    @Autowired
    PointLedgerDetailRepository details;

    @Test
    void 현재_잔액은_만료되지_않은_적립_remainingAmount만_합산한다() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-22T10:00:00+09:00");
        policies.save(CustomerPointPolicy.create(100L, 10_000L, now));
        ledgers.save(accrual("A", NORMAL, 300L, now.plusDays(1), now));
        ledgers.save(accrual("B", NORMAL, 500L, now, now.minusDays(1)));

        assertThat(ledgers.sumAvailableBalance(100L, now)).isEqualTo(300L);
    }

    @Test
    void 사용_대상은_수기_적립_만료일_발생시각_ID_순으로_조회한다() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-22T10:00:00+09:00");
        policies.save(CustomerPointPolicy.create(100L, 10_000L, now));
        ledgers.save(accrual("ID-FIRST", NORMAL, 100L, now.plusDays(2), now.plusHours(2)));
        ledgers.save(accrual("ID-SECOND", NORMAL, 100L, now.plusDays(2), now.plusHours(2)));
        ledgers.save(accrual("OCCURRED-EARLY", NORMAL, 100L, now.plusDays(2), now));
        ledgers.save(accrual("EXPIRY-EARLY", NORMAL, 100L, now.plusDays(1), now.plusHours(3)));
        ledgers.save(accrual("MANUAL-LATE", MANUAL, 100L, now.plusDays(30), now.plusHours(4)));
        ledgers.flush();

        assertThat(ledgers.findSpendableAccruals(100L, now))
                .extracting(PointLedger::getPointKey)
                .containsExactly(
                        "MANUAL-LATE",
                        "EXPIRY-EARLY",
                        "OCCURRED-EARLY",
                        "ID-FIRST",
                        "ID-SECOND");
    }

    private PointLedger accrual(
            String pointKey, AccrualTransactionType transactionType, long amount,
            OffsetDateTime expiresAt, OffsetDateTime occurredAt) {
        return PointLedger.createAccrual(
                100L, pointKey, "request-" + pointKey, transactionType, null,
                amount, amount, expiresAt, occurredAt, LocalDate.from(occurredAt));
    }
}
