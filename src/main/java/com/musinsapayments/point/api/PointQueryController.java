package com.musinsapayments.point.api;

import com.musinsapayments.point.api.response.AccrualHistoryResponse;
import com.musinsapayments.point.api.response.PageResponse;
import com.musinsapayments.point.api.response.PointBalanceResponse;
import com.musinsapayments.point.api.response.TransactionResponse;
import com.musinsapayments.point.application.PointQueryService;
import com.musinsapayments.point.application.query.TransactionSearchCondition;
import com.musinsapayments.point.domain.ledger.PointType;
import java.beans.PropertyEditorSupport;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/points")
public class PointQueryController {

    private final PointQueryService queryService;

    public PointQueryController(PointQueryService queryService) {
        this.queryService = queryService;
    }

    @InitBinder
    void bindLocalDate(WebDataBinder binder) {
        binder.registerCustomEditor(LocalDate.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                try {
                    setValue(LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE));
                } catch (DateTimeParseException exception) {
                    throw new IllegalArgumentException(exception);
                }
            }
        });
    }

    /**
     * 고객별 포인트 잔액 조회
     * @param customerId 고객 아이디
     * @return 잔액 정보
     */
    @GetMapping("/balance")
    public PointBalanceResponse balance(@RequestParam @Positive long customerId) {
        return PointBalanceResponse.from(queryService.balance(customerId));
    }

    /**
     * 고객별 포인트 거래 내역 조회
     * @param customerId 고객 아이디
     * @param pointType 포인트 타입
     * @param fromDate 시작 일자
     * @param toDate 종료 일자
     * @param page 페이지
     * @param size 사이즈
     * @return 거래 내역
     */
    @GetMapping("/transactions")
    public PageResponse<TransactionResponse> transactions(
            @RequestParam @Positive long customerId,
            @RequestParam(required = false) PointType pointType,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate toDate,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        TransactionSearchCondition condition = new TransactionSearchCondition(
                customerId, pointType, fromDate, toDate, page, size);
        return PageResponse.from(queryService.transactions(condition), TransactionResponse::fromSummary);
    }

    /**
     * 특정 포인트 키 원장 이력 조회
     * @param pointKey 포인트 키
     * @return 포인트 이력
     */
    @GetMapping("/transactions/{pointKey}")
    public TransactionResponse transaction(@PathVariable UUID pointKey) {
        return TransactionResponse.fromDetail(queryService.transaction(pointKey.toString()));
    }

    /**
     * 특정 포인트 키 거래 내역 조회
     * @param pointKey 포인트 키
     * @return 거래 이력
     */
    @GetMapping("/accruals/{pointKey}/history")
    public AccrualHistoryResponse accrualHistory(@PathVariable UUID pointKey) {
        return AccrualHistoryResponse.from(queryService.accrualHistory(pointKey.toString()));
    }
}
