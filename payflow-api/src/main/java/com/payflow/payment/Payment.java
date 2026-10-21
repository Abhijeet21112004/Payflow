package com.payflow.payment;

import java.time.Instant;
import java.util.UUID;

public record Payment(UUID id, UUID merchantId, long amount, String currency, String status,
                      long refundedAmount, String mockOutcome, String failureReason,
                      String customerEmail, Instant createdAt, Instant updatedAt) {
}
