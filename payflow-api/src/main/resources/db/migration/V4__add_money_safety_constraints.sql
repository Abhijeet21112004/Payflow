-- Database-level backstops for the race conditions fixed with SELECT ... FOR UPDATE.
-- Even if the locking code had a bug, the database itself now refuses the corrupted result.

-- A payment can be posted to the ledger as a capture at most once (and each refund at most once).
ALTER TABLE ledger_transactions
  ADD CONSTRAINT ledger_transactions_one_per_reference UNIQUE (type, reference_id);

-- A payment can never be refunded below zero or above its amount.
ALTER TABLE payments
  ADD CONSTRAINT payments_refund_within_amount CHECK (refunded_amount >= 0 AND refunded_amount <= amount);
