package com.payflow.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.payflow.ApiTestSupport;
import com.payflow.Concurrently;

/**
 * Property P1: N parallel captures of ONE payment must move money exactly once.
 * Each request uses its own Idempotency-Key, so these are genuinely different requests, not retries.
 */
class CaptureConcurrencyTest extends ApiTestSupport {

    private static final int THREADS = 50;
    private static final long AMOUNT = 50_000;   // ₹500.00

    @Test
    void parallelCapturesMoveMoneyExactlyOnce() throws Exception {
        var merchant = registerMerchant();
        UUID paymentId = createPayment(merchant, AMOUNT, "success");

        List<HttpResponse<String>> responses = Concurrently.run(THREADS,
                () -> apiPost("/payments/" + paymentId + "/capture", merchant.apiKey(), newKey(), null));

        long ok = responses.stream().filter(r -> r.statusCode() == 200).count();
        long conflicts = responses.stream().filter(r -> r.statusCode() == 409).count();
        int ledgerTransactions = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE type = 'capture' AND reference_id = ?",
                Integer.class, paymentId);
        long balance = balanceOf(merchant.id());

        System.out.printf("""

                === Capture race: %d parallel captures of one ₹%.2f payment ===
                successful captures  : %d   (expected 1)
                rejected with 409    : %d   (expected %d)
                ledger transactions  : %d   (expected 1)
                merchant balance     : ₹%.2f   (expected ₹%.2f)
                %n""", THREADS, AMOUNT / 100.0, ok, conflicts, THREADS - 1,
                ledgerTransactions, balance / 100.0, AMOUNT / 100.0);

        assertEquals(1, ok, "exactly one capture should succeed");
        assertEquals(THREADS - 1, conflicts, "every other capture should be rejected");
        assertEquals(1, ledgerTransactions, "the payment should be posted to the ledger once");
        assertEquals(AMOUNT, balance, "the merchant should be credited once");
    }
}
