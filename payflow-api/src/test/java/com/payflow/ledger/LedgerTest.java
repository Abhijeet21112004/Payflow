package com.payflow.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.payflow.ApiTestSupport;

class LedgerTest extends ApiTestSupport {

    @Autowired
    LedgerService ledgerService;

    @Autowired
    PlatformTransactionManager txManager;

    @Test
    void newMerchantStartsAtZero() throws Exception {
        var merchant = registerMerchant();

        var response = get("/merchants/me/balance", merchant.token());

        assertEquals(200, response.statusCode());
        assertEquals(0, body(response).get("balance").asLong());
        assertEquals("INR", body(response).get("currency").asString());
    }

    @Test
    void captureAndRefundMoveTheBalanceAndAlwaysBalance() throws Exception {
        var merchant = registerMerchant();
        UUID paymentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        inTransaction(() -> ledgerService.postCapture(merchant.id(), paymentId, 50_000));
        inTransaction(() -> ledgerService.postRefund(merchant.id(), refundId, 20_000));

        assertEquals(30_000, body(get("/merchants/me/balance", merchant.token())).get("balance").asLong());

        // Double-entry: within every transaction, debits equal credits.
        Integer unbalanced = jdbc.queryForObject("""
                SELECT count(*) FROM (
                  SELECT transaction_id FROM ledger_entries
                  GROUP BY transaction_id
                  HAVING SUM(CASE WHEN direction = 'debit' THEN amount ELSE -amount END) <> 0
                ) t
                """, Integer.class);
        assertEquals(0, unbalanced);
    }

    @Test
    void postingOutsideATransactionIsRefused() throws Exception {
        var merchant = registerMerchant();

        assertThrows(IllegalTransactionStateException.class,
                () -> ledgerService.postCapture(merchant.id(), UUID.randomUUID(), 100));
    }

    @Test
    void ledgerRowsCannotBeChangedOrDeleted() throws Exception {
        var merchant = registerMerchant();
        inTransaction(() -> ledgerService.postCapture(merchant.id(), UUID.randomUUID(), 100));

        var update = assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE ledger_entries SET amount = 999999"));
        assertTrue(update.getMessage().contains("append-only"));

        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM ledger_entries"));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM ledger_transactions"));
    }

    @Test
    void historyIsPagedNewestFirst() throws Exception {
        var merchant = registerMerchant();
        for (int i = 1; i <= 5; i++) {
            long amount = i * 100L;
            inTransaction(() -> ledgerService.postCapture(merchant.id(), UUID.randomUUID(), amount));
        }

        var page1 = body(get("/merchants/me/ledger?limit=3", merchant.token()));
        assertEquals(3, page1.get("entries").size());
        assertEquals(500, page1.get("entries").get(0).get("amount").asLong());   // newest first
        String cursor = page1.get("next_cursor").asString();
        assertNotNull(cursor);

        var page2 = body(get("/merchants/me/ledger?limit=3&cursor=" + cursor, merchant.token()));
        assertEquals(2, page2.get("entries").size());
        assertEquals(100, page2.get("entries").get(1).get("amount").asLong());   // oldest last
        assertTrue(page2.get("next_cursor").isNull());
        assertNull(page2.get("next_cursor").asString(null));
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> work.run());
    }
}
