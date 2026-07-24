package com.musinsapayments.point.api;

import com.musinsapayments.point.api.request.PointUseCancellationRequest;
import com.musinsapayments.point.api.request.PointUseRequest;
import com.musinsapayments.point.api.response.PointMutationResponse;
import com.musinsapayments.point.application.PointUseCancellationService;
import com.musinsapayments.point.application.PointUseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/points/uses")
public class PointUseController {

    private final PointUseService useService;
    private final PointUseCancellationService cancellationService;

    public PointUseController(
            PointUseService useService,
            PointUseCancellationService cancellationService) {
        this.useService = useService;
        this.cancellationService = cancellationService;
    }

    /**
     * 포인트 사용
     * @param request 사용 정보
     * @return 사용 결과
     */
    @PostMapping
    public PointMutationResponse use(@Valid @RequestBody PointUseRequest request) {
        return PointMutationResponse.from(useService.use(request.toCommand()));
    }

    /**
     * 포인트 사용 취소
     * @param request 사용 취소 정보
     * @return 사용 취소 결과
     */
    @PostMapping("/cancel")
    public PointMutationResponse cancel(@Valid @RequestBody PointUseCancellationRequest request) {
        return PointMutationResponse.from(cancellationService.cancel(request.toCommand()));
    }
}
