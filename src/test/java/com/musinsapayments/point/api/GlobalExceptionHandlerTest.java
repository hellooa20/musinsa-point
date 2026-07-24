package com.musinsapayments.point.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.musinsapayments.point.api.error.GlobalExceptionHandler;
import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({ExceptionTestController.class, GlobalExceptionHandler.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUpClock() {
        given(clock.getZone()).willReturn(ZoneOffset.UTC);
        given(clock.instant()).willReturn(Instant.parse("2026-07-22T00:00:00Z"));
    }

    @Test
    void 도메인_오류를_400_404_409_422_500으로_매핑한다() throws Exception {
        assertError(get("/test-errors/point/INVALID_REQUEST"), status().isBadRequest(), "INVALID_REQUEST");
        assertError(get("/test-errors/point/POINT_NOT_FOUND"), status().isNotFound(), "POINT_NOT_FOUND");
        assertError(get("/test-errors/point/REQUEST_ID_CONFLICT"), status().isConflict(), "REQUEST_ID_CONFLICT");
        assertError(get("/test-errors/point/HOLDING_LIMIT_EXCEEDED"),
                status().isUnprocessableEntity(), "HOLDING_LIMIT_EXCEEDED");
        assertError(get("/test-errors/point/INTERNAL_ERROR"),
                status().isInternalServerError(), "INTERNAL_ERROR");
    }

    @Test
    void 무결성_제약명과_입력_오류를_각각_변환한다() throws Exception {
        assertError(get("/test-errors/integrity/request"),
                status().isConflict(), "REQUEST_ID_CONFLICT");
        assertError(get("/test-errors/integrity/order"),
                status().isConflict(), "ORDER_NUMBER_CONFLICT");
        assertError(get("/test-errors/type/not-a-uuid"), status().isBadRequest(), "INVALID_REQUEST");
        assertError(post("/test-errors/body")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"not-a-uuid\"}"),
                status().isBadRequest(), "INVALID_REQUEST");
        assertError(get("/test-errors/illegal"), status().isBadRequest(), "INVALID_REQUEST");
    }

    @Test
    void 세_락_오류와_예상하지_못한_오류를_안전하게_변환한다() throws Exception {
        assertError(get("/test-errors/lock/cannot-acquire"),
                status().isServiceUnavailable(), "LOCK_TIMEOUT");
        assertError(get("/test-errors/lock/pessimistic"),
                status().isServiceUnavailable(), "LOCK_TIMEOUT");
        assertError(get("/test-errors/lock/timeout"),
                status().isServiceUnavailable(), "LOCK_TIMEOUT");

        mvc.perform(get("/test-errors/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(PointErrorCode.INTERNAL_ERROR.message()))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    private void assertError(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            org.springframework.test.web.servlet.ResultMatcher statusMatcher,
            String code) throws Exception {
        mvc.perform(request)
                .andExpect(statusMatcher)
                .andExpect(jsonPath("$.code").value(code));
    }
}

@org.springframework.web.bind.annotation.RestController
class ExceptionTestController {

    @org.springframework.web.bind.annotation.GetMapping("/test-errors/point/{code}")
    void point(@org.springframework.web.bind.annotation.PathVariable PointErrorCode code) {
        throw new PointException(code);
    }

    @org.springframework.web.bind.annotation.GetMapping("/test-errors/integrity/{constraint}")
    void integrity(@org.springframework.web.bind.annotation.PathVariable String constraint) {
        String name = constraint.equals("request")
                ? "uk_point_ledger_request_id" : "uk_point_ledger_order_number";
        throw new DataIntegrityViolationException("integrity", new RuntimeException(name));
    }

    @org.springframework.web.bind.annotation.GetMapping("/test-errors/type/{pointKey}")
    void type(@org.springframework.web.bind.annotation.PathVariable java.util.UUID pointKey) {
    }

    @org.springframework.web.bind.annotation.PostMapping("/test-errors/body")
    void body(@org.springframework.web.bind.annotation.RequestBody ErrorRequest request) {
    }

    @org.springframework.web.bind.annotation.GetMapping("/test-errors/illegal")
    void illegal() {
        throw new IllegalArgumentException("invalid");
    }

    @org.springframework.web.bind.annotation.GetMapping("/test-errors/lock/{type}")
    void lock(@org.springframework.web.bind.annotation.PathVariable String type) {
        if (type.equals("cannot-acquire")) {
            throw new CannotAcquireLockException("lock");
        }
        if (type.equals("pessimistic")) {
            throw new PessimisticLockingFailureException("lock");
        }
        throw new jakarta.persistence.LockTimeoutException("lock");
    }

    @org.springframework.web.bind.annotation.GetMapping("/test-errors/unexpected")
    void unexpected() {
        throw new IllegalStateException("secret diagnostic detail");
    }

    record ErrorRequest(java.util.UUID requestId) {
    }
}
