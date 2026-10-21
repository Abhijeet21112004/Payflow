package com.payflow.webhook;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.payflow.payment.Payment;

import tools.jackson.databind.json.JsonMapper;

/**
 * Records webhook events in the outbox. MANDATORY: it only runs inside the caller's transaction,
 * so the event commits together with the payment change, or not at all. Nothing is sent here;
 * WebhookDispatcher sends events after they've committed.
 */
@Service
public class WebhookService {

    public static final String PAYMENT_CAPTURED = "payment.captured";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String PAYMENT_REFUNDED = "payment.refunded";

    private final WebhookRepository events;
    private final JsonMapper json;

    public WebhookService(WebhookRepository events, JsonMapper json) {
        this.events = events;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String eventType, Payment payment, UUID refundId) {
        UUID eventId = UUID.randomUUID();
        var payload = new WebhookPayload(eventId, eventType, Instant.now(),
                new WebhookPayload.Data(payment.id(), payment.amount(), payment.currency(), payment.status(),
                        payment.refundedAmount(), refundId));
        events.insert(eventId, payment.merchantId(), payment.id(), eventType, json.writeValueAsString(payload));
    }
}
