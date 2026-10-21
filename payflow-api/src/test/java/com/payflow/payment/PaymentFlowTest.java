package com.payflow.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.payflow.ApiTestSupport;

/** The normal (one request at a time) behaviour of create → capture → refund. */
class PaymentFlowTest extends ApiTestSupport {

    @Test
    void successfulPaymentIsAuthorized() throws Exception {
        var merchant = registerMerchant();

        var response = apiPost("/payments", merchant.apiKey(), newKey(),
                Map.of("amount", 50_000, "currency", "INR", "mock_outcome", "success"));

        assertEquals(201, response.statusCode());
        assertEquals("authorized", body(response).get("status").asString());
        assertEquals(50_000, body(response).get("amount").asLong());
    }

    @Test
    void declinedPaymentFails() throws Exception {
        var merchant = registerMerchant();

        var response = apiPost("/payments", merchant.apiKey(), newKey(),
                Map.of("amount", 50_000, "currency", "INR", "mock_outcome", "decline"));

        assertEquals("failed", body(response).get("status").asString());
        assertEquals("card_declined", body(response).get("failure_reason").asString());
    }

    @Test
    void timedOutPaymentFails() throws Exception {
        var merchant = registerMerchant();

        var response = apiPost("/payments", merchant.apiKey(), newKey(),
                Map.of("amount", 50_000, "currency", "INR", "mock_outcome", "timeout"));

        assertEquals("failed", body(response).get("status").asString());
        assertEquals("processor_timeout", body(response).get("failure_reason").asString());
    }

    @Test
    void captureCreditsTheMerchant() throws Exception {
        var merchant = registerMerchant();
        UUID paymentId = createPayment(merchant, 50_000, "success");

        var capture = apiPost("/payments/" + paymentId + "/capture", merchant.apiKey(), newKey(), null);

        assertEquals(200, capture.statusCode());
        assertEquals("captured", body(capture).get("status").asString());
        assertEquals(50_000, balanceOf(merchant.id()));
        assertEquals("captured", body(apiGet("/payments/" + paymentId, merchant.apiKey())).get("status").asString());
    }

    @Test
    void partialThenFullRefund() throws Exception {
        var merchant = registerMerchant();
        UUID paymentId = createCapturedPayment(merchant, 50_000);

        var partial = apiPost("/payments/" + paymentId + "/refund", merchant.apiKey(), newKey(), Map.of("amount", 20_000));
        assertEquals(201, partial.statusCode());
        assertEquals("partially_refunded", body(partial).get("payment").get("status").asString());
        assertEquals(30_000, balanceOf(merchant.id()));

        // No amount = refund whatever is left.
        var rest = apiPost("/payments/" + paymentId + "/refund", merchant.apiKey(), newKey(), Map.of());
        assertEquals(201, rest.statusCode());
        assertEquals(30_000, body(rest).get("amount").asLong());
        assertEquals("refunded", body(rest).get("payment").get("status").asString());
        assertEquals(0, balanceOf(merchant.id()));
    }

    @Test
    void refundingMoreThanRemainsIsRejected() throws Exception {
        var merchant = registerMerchant();
        UUID paymentId = createCapturedPayment(merchant, 50_000);

        var response = apiPost("/payments/" + paymentId + "/refund", merchant.apiKey(), newKey(), Map.of("amount", 60_000));

        assertEquals(422, response.statusCode());
        assertEquals("refund_exceeds_remaining", errorCode(response));
        assertEquals(50_000, balanceOf(merchant.id()));
    }

    @Test
    void failedPaymentCannotBeCaptured() throws Exception {
        var merchant = registerMerchant();
        UUID paymentId = createPayment(merchant, 50_000, "decline");

        var response = apiPost("/payments/" + paymentId + "/capture", merchant.apiKey(), newKey(), null);

        assertEquals(409, response.statusCode());
        assertEquals("invalid_state", errorCode(response));
        assertEquals(0, balanceOf(merchant.id()));
    }

    @Test
    void anotherMerchantsPaymentLooksMissing() throws Exception {
        var owner = registerMerchant();
        var stranger = registerMerchant();
        UUID paymentId = createPayment(owner, 50_000, "success");

        assertEquals(404, apiGet("/payments/" + paymentId, stranger.apiKey()).statusCode());
        assertEquals(404, apiPost("/payments/" + paymentId + "/capture", stranger.apiKey(), newKey(), null).statusCode());
    }

    @Test
    void onlyInrIsAccepted() throws Exception {
        var merchant = registerMerchant();

        var response = apiPost("/payments", merchant.apiKey(), newKey(),
                Map.of("amount", 50_000, "currency", "USD", "mock_outcome", "success"));

        assertEquals(400, response.statusCode());
        assertEquals("unsupported_currency", errorCode(response));
    }

    @Test
    void paymentsNeedAValidApiKey() throws Exception {
        var noKey = apiGet("/payments/" + UUID.randomUUID(), "");
        assertEquals(401, noKey.statusCode());
        assertEquals("missing_api_key", errorCode(noKey));

        var wrongKey = apiGet("/payments/" + UUID.randomUUID(), "pf_live_this-key-does-not-exist");
        assertEquals(401, wrongKey.statusCode());
        assertEquals("invalid_api_key", errorCode(wrongKey));
    }
}
