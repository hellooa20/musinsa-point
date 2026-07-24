package com.musinsapayments.point.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.musinsapayments.point.api.error.GlobalExceptionHandler;
import com.musinsapayments.point.application.PointAccrualCancellationService;
import com.musinsapayments.point.application.PointAccrualService;
import com.musinsapayments.point.application.PointMutationResult;
import com.musinsapayments.point.application.PointPolicyService;
import com.musinsapayments.point.application.PointUseCancellationService;
import com.musinsapayments.point.application.PointUseService;
import com.musinsapayments.point.application.query.PointPolicyResult;
import com.musinsapayments.point.domain.ledger.PointType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({
        PointPolicyController.class,
        PointAccrualController.class,
        AdminPointAccrualController.class,
        PointUseController.class,
        GlobalExceptionHandler.class
})
class PointMutationControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PointAccrualService accrualService;

    @MockitoBean
    private PointAccrualCancellationService accrualCancellationService;

    @MockitoBean
    private PointUseService useService;

    @MockitoBean
    private PointUseCancellationService useCancellationService;

    @MockitoBean
    private PointPolicyService policyService;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUpClock() {
        given(clock.getZone()).willReturn(ZoneOffset.UTC);
        given(clock.instant()).willReturn(Instant.parse("2026-07-22T00:00:00Z"));
    }

    @Test
    void 일반_적립은_200이며_details와_remainingAmount를_반환하지_않는다() throws Exception {
        given(accrualService.accrueNormal(any())).willReturn(mutationResult());

        mvc.perform(post("/api/v1/points/accruals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"requestId":"11111111-1111-1111-1111-111111111111",
                             "customerId":100,"amount":1000,"validityDays":365}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pointKey").exists())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.remainingAmount").doesNotExist())
                .andExpect(jsonPath("$.transactionDate").value("20260722"));
    }

    @Test
    void 적립취소는_축약된_cancel_경로와_body_pointKey를_사용한다() throws Exception {
        given(accrualCancellationService.cancel(any())).willReturn(mutationResult());

        mvc.perform(post("/api/v1/points/accruals/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"requestId":"22222222-2222-2222-2222-222222222222",
                             "customerId":100,
                             "accrualPointKey":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}
                            """))
                .andExpect(status().isOk());

        then(accrualCancellationService).should().cancel(argThat(command ->
                command.customerId() == 100L
                        && command.accrualPointKey().equals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")));
    }

    @Test
    void 정책과_관리자_적립과_사용_및_사용취소는_body를_각_service_command로_전달한다() throws Exception {
        given(policyService.change(any())).willReturn(new PointPolicyResult(100L, 10000L));
        given(accrualService.accrueManual(any())).willReturn(mutationResult());
        given(useService.use(any())).willReturn(mutationResult());
        given(useCancellationService.cancel(any())).willReturn(mutationResult());

        mvc.perform(put("/api/v1/point-policies/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdingLimit\":10000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdingLimit").value(10000));
        mvc.perform(post("/api/v1/admin/points/accruals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"requestId":"33333333-3333-3333-3333-333333333333",
                             "customerId":100,"amount":1000,"validityDays":30}
                            """))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/points/uses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"requestId":"44444444-4444-4444-4444-444444444444",
                             "customerId":100,"orderNumber":"ORDER-1","amount":500}
                            """))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/points/uses/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"requestId":"55555555-5555-5555-5555-555555555555",
                             "customerId":100,"usePointKey":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                             "cancelOrderNumber":"CANCEL-1","amount":500}
                            """))
                .andExpect(status().isOk());

        then(policyService).should().change(argThat(command ->
                command.customerId() == 100L && command.holdingLimit() == 10000L));
        then(accrualService).should().accrueManual(argThat(command ->
                command.customerId() == 100L && command.amount() == 1000L
                        && command.validityDays() == 30));
        then(useService).should().use(argThat(command ->
                command.customerId() == 100L && command.orderNumber().equals("ORDER-1")
                        && command.amount() == 500L));
        then(useCancellationService).should().cancel(argThat(command ->
                command.customerId() == 100L
                        && command.usePointKey().equals("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
                        && command.cancelOrderNumber().equals("CANCEL-1")
                        && command.amount() == 500L));
    }

    @Test
    void null_requestId와_customerId_0과_amount_0은_각각_400이다() throws Exception {
        mvc.perform(post("/api/v1/points/accruals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":100,\"amount\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mvc.perform(post("/api/v1/points/accruals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"requestId":"11111111-1111-1111-1111-111111111111",
                             "customerId":0,"amount":1}
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mvc.perform(post("/api/v1/points/uses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"requestId":"11111111-1111-1111-1111-111111111111",
                             "customerId":100,"orderNumber":"ORDER-1","amount":0}
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 주문번호_길이와_사용취소_target_주문번호_금액을_검증한다() throws Exception {
        mvc.perform(post("/api/v1/points/uses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"requestId":"11111111-1111-1111-1111-111111111111",
                             "customerId":100,"orderNumber":"%s","amount":1}
                            """.formatted("a".repeat(101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mvc.perform(post("/api/v1/points/uses/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"requestId":"11111111-1111-1111-1111-111111111111",
                             "customerId":100,"usePointKey":"not-a-uuid",
                             "cancelOrderNumber":"CANCEL-1","amount":1}
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/points/uses/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"requestId":"11111111-1111-1111-1111-111111111111",
                             "customerId":100,"usePointKey":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                             "cancelOrderNumber":"","amount":1}
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/points/uses/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"requestId":"11111111-1111-1111-1111-111111111111",
                             "customerId":100,"usePointKey":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                             "cancelOrderNumber":"CANCEL-1","amount":0}
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private PointMutationResult mutationResult() {
        return new PointMutationResult(
                "point-key", 100L, PointType.ACCRUAL, null, null, 1000L, 1000L,
                OffsetDateTime.parse("2026-07-22T09:00:00+09:00"), LocalDate.of(2026, 7, 22),
                OffsetDateTime.parse("2027-07-22T09:00:00+09:00"));
    }
}
