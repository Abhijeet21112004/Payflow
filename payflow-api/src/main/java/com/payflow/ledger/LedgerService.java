package com.payflow.ledger;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The double-entry ledger. Every movement of money is one transaction with two equal entries:
 *
 *   capture ₹500:  debit processor_clearing 50000, credit merchant 50000
 *   refund  ₹200:  debit merchant 20000,           credit processor_clearing 20000
 *
 * Posting methods are MANDATORY: they refuse to run unless the caller already has a database
 * transaction open, so a ledger write can never be committed separately from the payment change it belongs to.
 */
@Service
public class LedgerService {

    private final LedgerRepository ledger;

    public LedgerService(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void openMerchantAccount(UUID merchantId) {
        ledger.insertMerchantAccount(merchantId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void postCapture(UUID merchantId, UUID paymentId, long amount) {
        UUID tx = ledger.insertTransaction("capture", paymentId);
        ledger.insertEntry(tx, ledger.clearingAccountId(), "debit", amount);
        ledger.insertEntry(tx, ledger.merchantAccountId(merchantId), "credit", amount);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void postRefund(UUID merchantId, UUID refundId, long amount) {
        UUID tx = ledger.insertTransaction("refund", refundId);
        ledger.insertEntry(tx, ledger.merchantAccountId(merchantId), "debit", amount);
        ledger.insertEntry(tx, ledger.clearingAccountId(), "credit", amount);
    }

    public long merchantBalance(UUID merchantId) {
        return ledger.balance(ledger.merchantAccountId(merchantId));
    }

    public LedgerPage merchantHistory(UUID merchantId, int limit, String cursor) {
        int pageSize = Math.clamp(limit, 1, 100);
        Cursor after = cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor);
        List<LedgerEntry> entries = ledger.entries(ledger.merchantAccountId(merchantId), pageSize, after);

        String next = null;
        if (entries.size() == pageSize) {
            LedgerEntry last = entries.getLast();
            next = new Cursor(last.createdAt(), last.id()).encode();
        }
        return new LedgerPage(entries, next);
    }

    public record LedgerPage(List<LedgerEntry> entries, String nextCursor) {
    }
}
