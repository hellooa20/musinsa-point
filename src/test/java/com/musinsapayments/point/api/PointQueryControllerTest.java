package com.musinsapayments.point.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.musinsapayments.point.api.error.GlobalExceptionHandler;
import com.musinsapayments.point.application.PointQueryService;
import com.musinsapayments.point.application.query.PageResult;
import com.musinsapayments.point.application.query.AccrualHistoryResult;
import com.musinsapayments.point.application.query.AccrualHistoryTransactionResult;
import com.musinsapayments.point.application.query.LedgerDetailResult;
import com.musinsapayments.point.application.query.PointBalanceResult;
import com.musinsapayments.point.application.query.TransactionDetailResult;
import com.musinsapayments.point.application.query.TransactionSummaryResult;
import com.musinsapayments.point.domain.ledger.PointType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({PointQueryController.class, GlobalExceptionHandler.class})
class PointQueryControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PointQueryService queryService;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUpClock() {
        given(clock.getZone()).willReturn(ZoneOffset.UTC);
        given(clock.instant()).willReturn(Instant.parse("2026-07-22T00:00:00Z"));
    }

    @Test
    void 거래_조회는_basic_iso_date로_응답한다() throws Exception {
        given(queryService.transactions(any())).willReturn(new PageResult<>(
                List.of(new TransactionSummaryResult(
                        "point-key", 100L, PointType.ACCRUAL, null, null, null, 1000L,
                        1000L, 1000L, "ACTIVE", OffsetDateTime.parse("2027-07-22T09:00:00+09:00"),
                        OffsetDateTime.parse("2026-07-22T09:00:00+09:00"), LocalDate.of(2026, 7, 22))),
                0, 20, 1, 1));

        mvc.perform(get("/api/v1/points/transactions?customerId=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transactionDate").value("20260722"));
    }

    @Test
    void 잔액과_거래상세와_적립이력을_실제_조회_경로로_반환한다() throws Exception {
        given(queryService.balance(100L)).willReturn(new PointBalanceResult(
                100L, 700L, OffsetDateTime.parse("2026-07-22T09:00:00+09:00")));
        given(queryService.transaction("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
                .willReturn(new TransactionDetailResult(
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 100L, PointType.USE, null,
                        null, "ORDER-1", 300L, null, 700L, "USED", null,
                        OffsetDateTime.parse("2026-07-22T09:00:00+09:00"), LocalDate.of(2026, 7, 22),
                        List.of(new LedgerDetailResult("source", null, 300L, 1))));
        given(queryService.accrualHistory("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
                .willReturn(new AccrualHistoryResult("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", List.of(
                        new AccrualHistoryTransactionResult(
                                new TransactionSummaryResult("history-key", 100L, PointType.USE, null,
                                        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "ORDER-1", 300L,
                                        null, 700L, "USED", null,
                                        OffsetDateTime.parse("2026-07-22T09:00:00+09:00"),
                                        LocalDate.of(2026, 7, 22)),
                                250L))));

        mvc.perform(get("/api/v1/points/balance?customerId=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(700));
        mvc.perform(get("/api/v1/points/transactions/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details[0].sourceAccrualPointKey").value("source"));
        mvc.perform(get("/api/v1/points/accruals/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions[0].pointKey").value("history-key"))
                .andExpect(jsonPath("$.transactions[0].amount").value(300))
                .andExpect(jsonPath("$.transactions[0].allocatedAmount").value(250));
    }

    @Test
    void 조회_파라미터의_범위와_형식을_검증한다() throws Exception {
        mvc.perform(get("/api/v1/points/transactions?customerId=100&size=101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mvc.perform(get("/api/v1/points/transactions?customerId=100&fromDate=20260723&toDate=20260722"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mvc.perform(get("/api/v1/points/transactions/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mvc.perform(get("/api/v1/points/transactions?customerId=100&fromDate=invalid-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mvc.perform(get("/api/v1/points/transactions?customerId=100&fromDate=2026-07-22&toDate=2026-07-23"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
