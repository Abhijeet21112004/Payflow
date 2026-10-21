package com.payflow.payment;

import java.time.Instant;
import java.util.UUID;

public record Refund(UUID id, UUID paymentId, long amount, Instant createdAt) {
}
