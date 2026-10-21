package com.payflow.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import com.payflow.ApiTestSupport;
import com.payflow.Concurrently;

/** Property P2: retrying a request with the same Idempotency-Key never repeats its effect. */
class IdempotencyTest extends ApiTestSupport {

    private static final Map<String, Object> PAYMENT =
            Map.of("amount", 50_000, "currency", "INR", "mock_outcome", "success");

    @Test
    void retriedCreateReturnsTheSamePaymentAndCreatesOnlyOne() throws Exception {
        var merchant = registerMerchant();
        String key = newKey();

        var first = apiPost("/payments", merchant.apiKey(), key, PAYMENT);
        var retry = apiPost("/payments", merchant.apiKey(), key, PAYMENT);

        assertEquals(201, first.statusCode());
        assertEquals(201, retry.statusCode());
        assertEquals(first.body(), retry.body());   // byte-for-byte identical
        assertEquals(1, paymentCount(merchant.id()));
    }

    @Test
    void retriedCaptureMovesMoneyOnce() throws Exception {
        var merchant = registerMerchant();
        UUID paymentId = createPayment(merchant, 50_000, "success");
        String key = newKey();

        var first = apiPost("/payments/" + paymentId + "/capture", merchant.apiKey(), key, null);
        var retry = apiPost("/payments/" + paymentId + "/capture", merchant.apiKey(), key, null);

        assertEquals(200, first.statusCode());
        assertEquals(200, retry.statusCode());   // a replay, not "409 already captured"
        assertEquals(first.body(), retry.body());
        assertEquals(50_000, balanceOf(merchant.id()));
    }

    @Test
    void retriedRefundRefundsOnce() throws Exception {
        var merchant = registerMerchant();
        UUID paymentId = createCapturedPayment(merchant, 50_000);
        String key = newKey();

        var first = apiPost("/payments/" + paymentId + "/refund", merchant.apiKey(), key, Map.of("amount", 10_000));
        var retry = apiPost("/payments/" + paymentId + "/refund", merchant.apiKey(), key, Map.of("amount", 10_000));

        assertEquals(first.body(), retry.body());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refunds WHERE payment_id = ?", Integer.class, paymentId));
        assertEquals(40_000, balanceOf(merchant.id()));
    }

    @Test
    void sameKeyWithADifferentBodyIsRejected() throws Exception {
        var merchant = registerMerchant();
        String key = newKey();
        apiPost("/payments", merchant.apiKey(), key, PAYMENT);

        var different = apiPost("/payments", merchant.apiKey(), key,
                Map.of("amount", 99_999, "currency", "INR", "mock_outcome", "success"));

        assertEquals(422, different.statusCode());
        assertEquals("idempotency_key_reused", errorCode(different));
        assertEquals(1, paymentCount(merchant.id()));
    }

    @Test
    void sameKeyOnADifferentEndpointIsRejected() throws Exception {
        var merchant = registerMerchant();
        String key = newKey();
        var created = apiPost("/payments", merchant.apiKey(), key, PAYMENT);
        String paymentId = body(created).get("id").asString();

        var capture = apiPost("/payments/" + paymentId + "/capture", merchant.apiKey(), key, null);

        assertEquals(422, capture.statusCode());
        assertEquals("idempotency_key_reused", errorCode(capture));
    }

    @Test
    void keysAreScopedPerMerchant() throws Exception {
        var a = registerMerchant();
        var b = registerMerchant();
        String sharedKey = newKey();

        assertEquals(201, apiPost("/payments", a.apiKey(), sharedKey, PAYMENT).statusCode());
        assertEquals(201, apiPost("/payments", b.apiKey(), sharedKey, PAYMENT).statusCode());
        assertEquals(1, paymentCount(a.id()));
        assertEquals(1, paymentCount(b.id()));
    }

    @Test
    void missingKeyIsRejected() throws Exception {
        var merchant = registerMerchant();

        var response = apiPost("/payments", merchant.apiKey(), null, PAYMENT);

        assertEquals(400, response.statusCode());
        assertEquals("missing_idempotency_key", errorCode(response));
        assertEquals(0, paymentCount(merchant.id()));
    }

    @Test
    void concurrentRetriesOfOneCreateMakeOnePayment() throws Exception {
        var merchant = registerMerchant();
        String key = newKey();

        List<HttpResponse<String>> responses = Concurrently.run(20,
                () -> apiPost("/payments", merchant.apiKey(), key, PAYMENT));

        Set<String> paymentIds = ConcurrentHashMap.newKeySet();
        for (var response : responses) {
            if (response.statusCode() == 201) {
                paymentIds.add(body(response).get("id").asString());
            } else {
                // Arrived while the first was still running: told to retry, nothing created.
                assertEquals(409, response.statusCode());
                assertEquals("request_in_progress", errorCode(response));
            }
        }
        assertTrue(paymentIds.size() <= 1, "every successful reply must describe the same payment");
        assertEquals(1, paymentCount(merchant.id()));
    }

    @Test
    void concurrentRetriesOfOneCaptureAllGetTheSameReply() throws Exception {
        var merchant = registerMerchant();
        UUID paymentId = createPayment(merchant, 50_000, "success");
        String key = newKey();

        List<HttpResponse<String>> responses = Concurrently.run(20,
                () -> apiPost("/payments/" + paymentId + "/capture", merchant.apiKey(), key, null));

        // Capture is a single transaction, so duplicates wait on the key's unique index
        // until the first commits, then replay its reply.
        String firstBody = responses.getFirst().body();
        for (var response : responses) {
            assertEquals(200, response.statusCode());
            assertEquals(firstBody, response.body());
        }
        assertEquals(50_000, balanceOf(merchant.id()));
    }

    private int paymentCount(UUID merchantId) {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE merchant_id = ?", Integer.class, merchantId);
    }
}
