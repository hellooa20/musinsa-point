package com.musinsapayments.point.application.command;

import java.util.UUID;

public record AccrualCommand(
        UUID requestId, long customerId, long amount, Integer validityDays) {

    public int normalizedValidityDays(int defaultDays) {
        return validityDays == null ? defaultDays : validityDays;
    }
}
