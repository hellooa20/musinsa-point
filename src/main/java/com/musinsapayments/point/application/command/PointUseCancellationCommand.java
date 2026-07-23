package com.musinsapayments.point.application.command;

import java.util.UUID;

public record PointUseCancellationCommand(
        UUID requestId,
        long customerId,
        String usePointKey,
        String cancelOrderNumber,
        long amount) {
}
