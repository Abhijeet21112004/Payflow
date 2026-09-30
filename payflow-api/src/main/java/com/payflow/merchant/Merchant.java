package com.payflow.merchant;

import java.time.Instant;
import java.util.UUID;

/** A merchant row as the application sees it. Never contains the password hash or raw keys. */
public record Merchant(UUID id, String name, String email, String apiKeyPrefix,
                       String webhookUrl, Instant createdAt) {
}
