package com.musinsapayments.point.api.request;

import jakarta.validation.constraints.PositiveOrZero;

public record ChangePointPolicyRequest(@PositiveOrZero long holdingLimit) {
}
