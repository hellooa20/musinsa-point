package com.musinsapayments.point.api.response;

import com.musinsapayments.point.application.query.PointPolicyResult;

public record PointPolicyResponse(long customerId, long holdingLimit) {

    public static PointPolicyResponse from(PointPolicyResult result) {
        return new PointPolicyResponse(result.customerId(), result.holdingLimit());
    }
}
