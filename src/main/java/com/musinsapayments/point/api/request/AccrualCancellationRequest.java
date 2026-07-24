package com.musinsapayments.point.api.request;

import com.musinsapayments.point.application.command.AccrualCancellationCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record AccrualCancellationRequest(
        @NotNull UUID requestId,
        @Positive long customerId,
        @NotNull UUID accrualPointKey) {

    public AccrualCancellationCommand toCommand() {
        return new AccrualCancellationCommand(requestId, customerId, accrualPointKey.toString());
    }
}
