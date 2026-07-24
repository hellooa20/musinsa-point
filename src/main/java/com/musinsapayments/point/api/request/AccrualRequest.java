package com.musinsapayments.point.api.request;

import com.musinsapayments.point.application.command.AccrualCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record AccrualRequest(
        @NotNull UUID requestId,
        @Positive long customerId,
        @Positive long amount,
        @Min(1) Integer validityDays) {

    public AccrualCommand toCommand() {
        return new AccrualCommand(requestId, customerId, amount, validityDays);
    }
}
