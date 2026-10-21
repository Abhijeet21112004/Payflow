package com.payflow.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.payflow.ApiTestSupport;

/**
 * Property P3: a capture is all-or-nothing.
 *
 * We make the database fail at the very LAST step of a capture (marking the payment 'captured'),
 * after the idempotency key was reserved and the ledger entries were written. Everything must roll back.
 */
class AtomicityTest extends ApiTestSupport {

    @Test
    void aFailureMidCaptureLeavesNoTrace() throws Exception {
        var merchant = registerMerchant();
        UUID paymentId = createPayment(merchant, 50_000, "success");
        String key = newKey();

        injectFailureWhenCaptured(paymentId);
        try {
            var response = apiPost("/payments/" + paymentId + "/capture", merchant.apiKey(), key, null);
            assertEquals(500, response.statusCode());
        } finally {
            removeInjectedFailure();
        }

        assertEquals("authorized", jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId));
        assertEquals(0, count("SELECT count(*) FROM ledger_transactions WHERE reference_id = ?", paymentId));
        assertEquals(0, count("SELECT count(*) FROM webhook_events WHERE payment_id = ?", paymentId));
        assertEquals(0, count("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?", key));
        assertEquals(0, balanceOf(merchant.id()));

        // Because nothing was kept, not even the key, the client's retry simply works.
        var retry = apiPost("/payments/" + paymentId + "/capture", merchant.apiKey(), key, null);
        assertEquals(200, retry.statusCode());
        assertEquals(50_000, balanceOf(merchant.id()));
    }

    private void injectFailureWhenCaptured(UUID paymentId) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_injected_failure() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'injected failure'; END $$ LANGUAGE plpgsql
                """);
        jdbc.execute("CREATE TRIGGER test_fail_capture BEFORE UPDATE ON payments FOR EACH ROW "
                + "WHEN (NEW.id = '" + paymentId + "' AND NEW.status = 'captured') "
                + "EXECUTE FUNCTION test_injected_failure()");
    }

    private void removeInjectedFailure() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_capture ON payments");
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }
}
