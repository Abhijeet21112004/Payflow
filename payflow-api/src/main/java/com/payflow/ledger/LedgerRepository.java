package com.payflow.ledger;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** All SQL for accounts, ledger_transactions and ledger_entries. There is no UPDATE or DELETE here, by design. */
@Repository
public class LedgerRepository {

    private static final RowMapper<LedgerEntry> ENTRY = (rs, rowNum) -> new LedgerEntry(
            rs.getObject("id", UUID.class),
            rs.getObject("transaction_id", UUID.class),
            rs.getString("type"),
            rs.getObject("reference_id", UUID.class),
            rs.getString("direction"),
            rs.getLong("amount"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertMerchantAccount(UUID merchantId) {
        jdbc.update("INSERT INTO accounts (merchant_id, type) VALUES (?, 'merchant')", merchantId);
    }

    public UUID merchantAccountId(UUID merchantId) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE merchant_id = ?", UUID.class, merchantId);
    }

    public UUID clearingAccountId() {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE type = 'processor_clearing'", UUID.class);
    }

    public UUID insertTransaction(String type, UUID referenceId) {
        return jdbc.queryForObject(
                "INSERT INTO ledger_transactions (type, reference_id) VALUES (?, ?) RETURNING id",
                UUID.class, type, referenceId);
    }

    public void insertEntry(UUID transactionId, UUID accountId, String direction, long amount) {
        jdbc.update("INSERT INTO ledger_entries (transaction_id, account_id, direction, amount) VALUES (?, ?, ?, ?)",
                transactionId, accountId, direction, amount);
    }

    /** Balance = credits − debits, summed from the entries every time. */
    public long balance(UUID accountId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'credit' THEN amount ELSE -amount END), 0)
                FROM ledger_entries WHERE account_id = ?
                """, Long.class, accountId);
    }

    /**
     * Newest entries first, at most {@code limit}. If {@code after} is given, only entries older than it
     * (keyset pagination: "continue after this row" instead of "skip N rows").
     */
    public List<LedgerEntry> entries(UUID accountId, int limit, Cursor after) {
        String select = """
                SELECT e.id, e.transaction_id, t.type, t.reference_id, e.direction, e.amount, e.created_at
                FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id
                WHERE e.account_id = ?
                """;
        String order = " ORDER BY e.created_at DESC, e.id DESC LIMIT ?";
        if (after == null) {
            return jdbc.query(select + order, ENTRY, accountId, limit);
        }
        return jdbc.query(select + " AND (e.created_at, e.id) < (?, ?)" + order, ENTRY,
                accountId, OffsetDateTime.ofInstant(after.createdAt(), ZoneOffset.UTC), after.id(), limit);
    }
}
