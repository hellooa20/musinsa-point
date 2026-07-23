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
        jdbcTemplate.update(
                "insert into customer_point_policy (customer_id, holding_limit, created_at, updated_at) values (?, ?, ?, ?)",
                100L, 10_000L, NOW, NOW);
        insertAccrual("point-key-1", "request-id");

        assertThatThrownBy(() -> insertAccrual("point-key-2", "request-id"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(rootCauseMessage(error))
                        .contains("uk_point_ledger_request_id"));
    }

    private void insertAccrual(String pointKey, String requestId) {
        jdbcTemplate.update("""
                insert into point_ledger (
                    customer_id, point_key, request_id, point_type, transaction_type,
                    amount, remaining_amount, balance_after, expires_at, occurred_at,
                    transaction_date, created_at, updated_at
                ) values (?, ?, ?, 'ACCRUAL', 'NORMAL', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                100L, pointKey, requestId, 100L, 100L, 100L, NOW.plusDays(1), NOW,
                NOW.toLocalDate(), NOW, NOW);
    }

    private String rootCauseMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage().toLowerCase(Locale.ROOT);
    }
}
