package com.payflow.ledger;

import java.time.Instant;
import java.util.UUID;

/** One line in a merchant's ledger history. */
public record LedgerEntry(UUID id, UUID transactionId, String type, UUID referenceId,
                          String direction, long amount, Instant createdAt) {
}
