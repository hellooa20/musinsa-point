package com.musinsapayments.point.api;

import com.musinsapayments.point.api.request.ChangePointPolicyRequest;
import com.musinsapayments.point.api.response.PointPolicyResponse;
import com.musinsapayments.point.application.PointPolicyService;
import com.musinsapayments.point.application.command.ChangePointPolicyCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/point-policies")
public class PointPolicyController {

    private final PointPolicyService policyService;

    public PointPolicyController(PointPolicyService policyService) {
        this.policyService = policyService;
    }

    /**
     * 고객별 포인트 정책 변경
     * @param customerId 고객 아이디
     * @param request 정책 정보
     * @return 정책 변경 결과
     */
    @PutMapping("/{customerId}")
    public PointPolicyResponse change(
            @PathVariable @Positive long customerId,
            @Valid @RequestBody ChangePointPolicyRequest request) {
        return PointPolicyResponse.from(policyService.change(
                new ChangePointPolicyCommand(customerId, request.holdingLimit())));
    }
}
