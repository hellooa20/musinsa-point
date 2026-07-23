package com.musinsapayments.point.application.command;

import java.util.UUID;

public record PointUseCommand(
        UUID requestId, long customerId, String orderNumber, long amount) {
}
