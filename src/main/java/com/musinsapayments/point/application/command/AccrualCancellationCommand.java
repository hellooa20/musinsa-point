package com.musinsapayments.point.application.command;

import java.util.UUID;

public record AccrualCancellationCommand(
        UUID requestId, long customerId, String accrualPointKey) {
}
