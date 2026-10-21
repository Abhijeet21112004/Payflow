package com.payflow.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.payflow.ApiTestSupport;

/** Property P5 (sender side): events are recorded with the change, delivered signed, and retried until they land. */
@TestPropertySource(properties = {
        "payflow.webhooks.poll-interval-ms=100",
        "payflow.webhooks.backoff=200ms,200ms,200ms,200ms,200ms"   // fast retries so the test doesn't wait an hour
})
class WebhookDeliveryTest extends ApiTestSupport {

    @Test
    void eachMoneyEventIsRecordedInTheOutbox() throws Exception {
        var merchant = registerMerchant();
        UUID declined = createPayment(merchant, 10_000, "decline");
        UUID paid = createCapturedPayment(merchant, 50_000);
        apiPost("/payments/" + paid + "/refund", merchant.apiKey(), newKey(), Map.of("amount", 5_000));

        assertEquals("payment.failed", eventTypes(declined));
        assertEquals("payment.captured,payment.refunded", eventTypes(paid));
    }

    @Test
    void deliveredWithASignatureTheMerchantCanVerify() throws Exception {
        try (var shop = new FakeMerchantServer(0)) {
            var merchant = registerMerchant();
            put("/merchants/me/webhook-url", merchant.token(), Map.of("webhook_url", shop.url()));

            UUID paymentId = createCapturedPayment(merchant, 50_000);
            UUID eventId = eventId(paymentId);
            awaitStatus(eventId, "delivered");

            var webhook = shop.received().getFirst();
            assertEquals(eventId.toString(), webhook.headers().get("event-id"));
            assertEquals("payment.captured", json.readTree(webhook.body()).get("type").asString());

            // Recompute the signature exactly as the merchant would, with the secret they got at registration.
            long timestamp = Long.parseLong(webhook.headers().get("timestamp"));
            assertEquals(WebhookSigner.sign(merchant.webhookSecret(), timestamp, webhook.body()),
                    webhook.headers().get("signature"));
        }
    }

    @Test
    void retriedUntilTheMerchantAcceptsIt() throws Exception {
        try (var shop = new FakeMerchantServer(2)) {   // merchant is "down" for the first 2 attempts
            var merchant = registerMerchant();
            put("/merchants/me/webhook-url", merchant.token(), Map.of("webhook_url", shop.url()));

            UUID eventId = eventId(createCapturedPayment(merchant, 50_000));
            awaitStatus(eventId, "delivered");

            assertEquals(3, attemptCount(eventId));
            assertEquals(3, shop.received().size());
            // Every retry carries the same event id, which is what lets the merchant de-duplicate.
            assertTrue(shop.received().stream().allMatch(r -> r.headers().get("event-id").equals(eventId.toString())));
        }
    }

    @Test
    void givesUpAfterSixFailedAttempts() throws Exception {
        try (var shop = new FakeMerchantServer(Integer.MAX_VALUE)) {   // merchant always fails
            var merchant = registerMerchant();
            put("/merchants/me/webhook-url", merchant.token(), Map.of("webhook_url", shop.url()));

            UUID eventId = eventId(createCapturedPayment(merchant, 50_000));
            awaitStatus(eventId, "failed");

            assertEquals(6, attemptCount(eventId));
        }
    }

    @Test
    void invalidWebhookUrlIsRejected() throws Exception {
        var merchant = registerMerchant();

        var response = put("/merchants/me/webhook-url", merchant.token(), Map.of("webhook_url", "not a url"));

        assertEquals(400, response.statusCode());
        assertEquals("invalid_webhook_url", errorCode(response));
    }

    private String eventTypes(UUID paymentId) {
        return String.join(",", jdbc.queryForList(
                "SELECT event_type FROM webhook_events WHERE payment_id = ? ORDER BY created_at, event_type",
                String.class, paymentId));
    }

    private UUID eventId(UUID paymentId) {
        return jdbc.queryForObject("SELECT id FROM webhook_events WHERE payment_id = ?", UUID.class, paymentId);
    }

    private int attemptCount(UUID eventId) {
        return jdbc.queryForObject("SELECT attempt_count FROM webhook_events WHERE id = ?", Integer.class, eventId);
    }

    private void awaitStatus(UUID eventId, String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String status = jdbc.queryForObject("SELECT status FROM webhook_events WHERE id = ?", String.class, eventId);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        fail("webhook " + eventId + " never reached status " + expected);
    }
}
