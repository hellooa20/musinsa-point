package com.musinsapayments.point.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PointSchemaConstraintTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-22T10:00:00+09:00");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void requestId는_유일해야_한다() {
        insertPolicy(100L);
        insertAccrual("point-key-1", "request-id");

        assertConstraint("uk_point_ledger_request_id",
                () -> insertAccrual("point-key-2", "request-id"));
    }

    @Test
    void pointKey는_유일해야_한다() {
        insertPolicy(100L);
        insertAccrual("point-key", "request-id-1");

        assertConstraint("uk_point_ledger_point_key",
                () -> insertAccrual("point-key", "request-id-2"));
    }

    @Test
    void 주문번호는_사용과_사용취소를_통틀어_유일해야_한다() {
        insertPolicy(100L);
        insertUse("use-1", "request-id-1", "order-1", null);

        assertConstraint("uk_point_ledger_order_number",
                () -> insertUse("use-cancel-1", "request-id-2", "order-1", "use-1"));
    }

    @Test
    void 원장은_존재하는_고객과_원거래만_참조해야_한다() {
        assertConstraint("fk_point_ledger_customer_policy",
                () -> insertAccrual("point-key", "request-id"));

        insertPolicy(100L);
        assertConstraint("fk_point_ledger_reference",
                () -> insertUse("use-cancel-1", "request-id-2", "cancel-order-1", "missing-use"));
    }

    @Test
    void 원장상세는_owner_source_target_원장을_모두_참조해야_한다() {
        insertPolicy(100L);
        insertAccrual("source", "request-source");
        insertUse("owner", "request-owner", "order-owner", null);

        assertConstraint("fk_point_ledger_detail_ledger",
                () -> insertDetail("missing-owner", "source", null, 100L, 1));
        assertConstraint("fk_point_ledger_detail_source",
                () -> insertDetail("owner", "missing-source", null, 100L, 1));
        assertConstraint("fk_point_ledger_detail_target",
                () -> insertDetail("owner", "source", "missing-target", 100L, 1));
    }

    @Test
    void 한_원장의_상세_sequence와_source는_중복될_수_없다() {
        insertPolicy(100L);
        insertAccrual("source-1", "request-source-1");
        insertAccrual("source-2", "request-source-2");
        insertUse("owner", "request-owner", "order-owner", null);
        insertDetail("owner", "source-1", null, 50L, 1);

        assertConstraint("uk_point_ledger_detail_sequence",
                () -> insertDetail("owner", "source-2", null, 50L, 1));
        assertConstraint("uk_point_ledger_detail_source",
                () -> insertDetail("owner", "source-1", null, 50L, 2));
    }

    @Test
    void 원장_금액과_잔액은_유효한_범위여야_한다() {
        insertPolicy(100L);

        assertConstraint("ck_point_ledger_amount",
                () -> insertAccrual("zero-amount", "request-zero", 0L, 0L));
        assertConstraint("ck_point_ledger_remaining",
                () -> insertAccrual("over-remaining", "request-over", 100L, 101L));
    }

    @Test
    void 원장_종류별_필수필드는_일관되어야_한다() {
        insertPolicy(100L);
        insertAccrual("source", "request-source");

        assertConstraint("ck_point_ledger_accrual_fields", () -> jdbcTemplate.update("""
                insert into point_ledger (
                    customer_id, point_key, request_id, point_type, transaction_type,
                    amount, remaining_amount, balance_after, occurred_at,
                    transaction_date, created_at, updated_at
                ) values (?, ?, ?, 'ACCRUAL', 'NORMAL', ?, ?, ?, ?, ?, ?, ?)
                """,
                100L, "accrual-without-expiry", "request-without-expiry",
                100L, 100L, 100L, NOW, NOW.toLocalDate(), NOW, NOW));
        assertConstraint("ck_point_ledger_order_fields", () -> jdbcTemplate.update("""
                insert into point_ledger (
                    customer_id, point_key, request_id, point_type,
                    amount, balance_after, occurred_at, transaction_date, created_at, updated_at
                ) values (?, ?, ?, 'USE', ?, ?, ?, ?, ?, ?)
                """,
                100L, "use-without-order", "request-without-order",
                100L, 0L, NOW, NOW.toLocalDate(), NOW, NOW));
        assertConstraint("ck_point_ledger_reference_fields", () -> jdbcTemplate.update("""
                insert into point_ledger (
                    customer_id, point_key, request_id, point_type, reference_point_key,
                    order_number, amount, balance_after, occurred_at,
                    transaction_date, created_at, updated_at
                ) values (?, ?, ?, 'USE', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                100L, "use-with-reference", "request-with-reference", "source",
                "order-with-reference", 100L, 0L, NOW, NOW.toLocalDate(), NOW, NOW));
    }

    @Test
    void 원장상세_금액과_순서는_양수여야_한다() {
        insertPolicy(100L);
        insertAccrual("source", "request-source");
        insertUse("owner", "request-owner", "order-owner", null);

        assertConstraint("ck_point_ledger_detail_amount",
                () -> insertDetail("owner", "source", null, 0L, 1));
        assertConstraint("ck_point_ledger_detail_sequence",
                () -> insertDetail("owner", "source", null, 100L, 0));
    }

    private void insertPolicy(long customerId) {
        jdbcTemplate.update(
                "insert into customer_point_policy (customer_id, holding_limit, created_at, updated_at) values (?, ?, ?, ?)",
                customerId, 10_000L, NOW, NOW);
    }

    private void insertAccrual(String pointKey, String requestId) {
        insertAccrual(pointKey, requestId, 100L, 100L);
    }

    private void insertAccrual(String pointKey, String requestId, long amount, long remainingAmount) {
        jdbcTemplate.update("""
                insert into point_ledger (
                    customer_id, point_key, request_id, point_type, transaction_type,
                    amount, remaining_amount, balance_after, expires_at, occurred_at,
                    transaction_date, created_at, updated_at
                ) values (?, ?, ?, 'ACCRUAL', 'NORMAL', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                100L, pointKey, requestId, amount, remainingAmount, 100L, NOW.plusDays(1), NOW,
                NOW.toLocalDate(), NOW, NOW);
    }

    private void insertUse(
            String pointKey, String requestId, String orderNumber, String referencePointKey) {
        String pointType = referencePointKey == null ? "USE" : "USE_CANCEL";
        jdbcTemplate.update("""
                insert into point_ledger (
                    customer_id, point_key, request_id, point_type, reference_point_key,
                    order_number, amount, balance_after, occurred_at,
                    transaction_date, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                100L, pointKey, requestId, pointType, referencePointKey,
                orderNumber, 100L, 0L, NOW, NOW.toLocalDate(), NOW, NOW);
    }

    private void insertDetail(
            String pointKey, String sourcePointKey, String targetPointKey,
            long amount, int sequenceNo) {
        jdbcTemplate.update("""
                insert into point_ledger_detail (
                    point_key, source_accrual_point_key, target_accrual_point_key,
                    amount, sequence_no, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """,
                pointKey, sourcePointKey, targetPointKey, amount, sequenceNo, NOW, NOW);
    }

    private void assertConstraint(
            String constraintName, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(rootCauseMessage(error)).contains(constraintName));
    }

    private String rootCauseMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage().toLowerCase(Locale.ROOT);
    }
}
