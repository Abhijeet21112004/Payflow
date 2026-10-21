package com.payflow.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.payflow.ApiTestSupport;
import com.payflow.Concurrently;

/**
 * Property P1: parallel partial refunds must never add up to more than was captured.
 * A ₹500 payment, 10 parallel refunds of ₹100 each: exactly 5 can succeed.
 */
class RefundConcurrencyTest extends ApiTestSupport {

    private static final int THREADS = 10;
    private static final long AMOUNT = 50_000;        // ₹500.00
    private static final long REFUND_EACH = 10_000;   // ₹100.00

    @Test
    void parallelRefundsNeverExceedTheCapturedAmount() throws Exception {
        var merchant = registerMerchant();
        UUID paymentId = createCapturedPayment(merchant, AMOUNT);

        List<HttpResponse<String>> responses = Concurrently.run(THREADS,
                () -> apiPost("/payments/" + paymentId + "/refund", merchant.apiKey(), newKey(),
                        Map.of("amount", REFUND_EACH)));

        long ok = responses.stream().filter(r -> r.statusCode() == 201).count();
        long refundedInLedger = jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.amount), 0) FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.transaction_id
                JOIN refunds r ON r.id = t.reference_id
                WHERE t.type = 'refund' AND e.direction = 'debit' AND r.payment_id = ?
                """, Long.class, paymentId);
        var payment = jdbc.queryForMap("SELECT status, refunded_amount FROM payments WHERE id = ?", paymentId);
        long refundedOnPayment = (Long) payment.get("refunded_amount");
        long balance = balanceOf(merchant.id());

        System.out.printf("""

                === Refund race: %d parallel ₹%.2f refunds of one ₹%.2f payment ===
                successful refunds         : %d   (expected 5)
                refunded per the ledger    : ₹%.2f   (expected ₹500.00)
                refunded per the payment   : ₹%.2f   (expected ₹500.00)
                payment status             : %s   (expected refunded)
                merchant balance           : ₹%.2f   (expected ₹0.00)
                %n""", THREADS, REFUND_EACH / 100.0, AMOUNT / 100.0, ok,
                refundedInLedger / 100.0, refundedOnPayment / 100.0, payment.get("status"), balance / 100.0);

        assertEquals(5, ok, "exactly five ₹100 refunds fit in ₹500");
        assertEquals(AMOUNT, refundedInLedger, "the ledger must not refund more than was captured");
        assertEquals(AMOUNT, refundedOnPayment, "the payment row must agree with the ledger");
        assertEquals("refunded", payment.get("status"));
        assertEquals(0, balance);
    }
}
