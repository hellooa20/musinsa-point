package com.musinsapayments.point.api.request;

import com.musinsapayments.point.application.command.PointUseCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record PointUseRequest(
        @NotNull UUID requestId,
        @Positive long customerId,
        @NotBlank @Size(max = 100) String orderNumber,
        @Positive long amount) {

    public PointUseCommand toCommand() {
        return new PointUseCommand(requestId, customerId, orderNumber, amount);
    }
}
