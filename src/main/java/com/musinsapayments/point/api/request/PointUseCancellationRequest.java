package com.musinsapayments.point.api.request;

import com.musinsapayments.point.application.command.PointUseCancellationCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record PointUseCancellationRequest(
        @NotNull UUID requestId,
        @Positive long customerId,
        @NotNull UUID usePointKey,
        @NotBlank @Size(max = 100) String cancelOrderNumber,
        @Positive long amount) {

    public PointUseCancellationCommand toCommand() {
        return new PointUseCancellationCommand(
                requestId, customerId, usePointKey.toString(), cancelOrderNumber, amount);
    }
}
