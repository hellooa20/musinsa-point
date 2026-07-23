package com.musinsapayments.point.application.command;

public record ChangePointPolicyCommand(long customerId, long holdingLimit) {

    public ChangePointPolicyCommand {
        if (customerId <= 0 || holdingLimit < 0) {
            throw new IllegalArgumentException("고객 ID와 보유 한도를 확인해 주세요.");
        }
    }
}
