package com.musinsapayments.point.api;

import com.musinsapayments.point.api.request.AccrualRequest;
import com.musinsapayments.point.api.response.PointMutationResponse;
import com.musinsapayments.point.application.PointAccrualService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/points/accruals")
public class AdminPointAccrualController {

    private final PointAccrualService accrualService;

    public AdminPointAccrualController(PointAccrualService accrualService) {
        this.accrualService = accrualService;
    }

    /**
     * 관리자 포인트 적립
     * @param request 적립 정보
     * @return 적립 결과
     */
    @PostMapping
    public PointMutationResponse accrue(@Valid @RequestBody AccrualRequest request) {
        return PointMutationResponse.from(accrualService.accrueManual(request.toCommand()));
    }
}
