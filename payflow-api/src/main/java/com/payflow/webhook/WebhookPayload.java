package com.payflow.webhook;

import java.time.Instant;
import java.util.UUID;

/**
 * The JSON body sent to the merchant, e.g.
 * {"id":"…","type":"payment.captured","created_at":"…",
 *  "data":{"payment_id":"…","amount":50000,"currency":"INR","status":"captured","refunded_amount":0,"refund_id":null}}
 */
public record WebhookPayload(UUID id, String type, Instant createdAt, Data data) {

    public record Data(UUID paymentId, long amount, String currency, String status,
                       long refundedAmount, UUID refundId) {
    }
}
