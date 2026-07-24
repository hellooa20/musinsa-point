package com.musinsapayments.point.api;

import com.musinsapayments.point.api.request.AccrualCancellationRequest;
import com.musinsapayments.point.api.request.AccrualRequest;
import com.musinsapayments.point.api.response.PointMutationResponse;
import com.musinsapayments.point.application.PointAccrualCancellationService;
import com.musinsapayments.point.application.PointAccrualService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/points/accruals")
public class PointAccrualController {

    private final PointAccrualService accrualService;
    private final PointAccrualCancellationService cancellationService;

    public PointAccrualController(
            PointAccrualService accrualService,
            PointAccrualCancellationService cancellationService) {
        this.accrualService = accrualService;
        this.cancellationService = cancellationService;
    }

    /**
     * 포인트 적립
     * @param request 적립 정보
     * @return 적립 결과
     */
    @PostMapping
    public PointMutationResponse accrue(@Valid @RequestBody AccrualRequest request) {
        return PointMutationResponse.from(accrualService.accrueNormal(request.toCommand()));
    }

    /**
     * 포인트 적립 취소
     * @param request 적립 정보
     * @return 적립 결과
     */
    @PostMapping("/cancel")
    public PointMutationResponse cancel(@Valid @RequestBody AccrualCancellationRequest request) {
        return PointMutationResponse.from(cancellationService.cancel(request.toCommand()));
    }
}
