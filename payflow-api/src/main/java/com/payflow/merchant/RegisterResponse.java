package com.payflow.merchant;

import java.util.UUID;

/** The reply to a successful registration. api_key and webhook_secret are shown only this once. */
public record RegisterResponse(UUID merchantId, String apiKey, String webhookSecret) {
}
