package com.payflow.concurrency;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.payflow.ApiTestSupport;

/**
 * The database backstops behind the row lock: even code that skipped the lock entirely
 * could not double-capture or over-refund, because the database rejects the write.
 */
class SafetyConstraintsTest extends ApiTestSupport {

    @Test
    void aPaymentCannotBeCapturedIntoTheLedgerTwice() throws Exception {
        var merchant = registerMerchant();
        UUID paymentId = createCapturedPayment(merchant, 50_000);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO ledger_transactions (type, reference_id) VALUES ('capture', ?)", paymentId));
    }

    @Test
    void aPaymentCannotBeRefundedBeyondItsAmount() throws Exception {
        var merchant = registerMerchant();
        UUID paymentId = createCapturedPayment(merchant, 50_000);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE payments SET refunded_amount = 50001 WHERE id = ?", paymentId));
    }
}
