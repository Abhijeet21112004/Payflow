-- Double-entry ledger. Balances are never stored; they are always summed from ledger_entries.

CREATE TABLE accounts (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  merchant_id  UUID UNIQUE REFERENCES merchants(id),   -- NULL for system accounts
  type         TEXT NOT NULL CHECK (type IN ('merchant', 'processor_clearing')),
  currency     TEXT NOT NULL DEFAULT 'INR',
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((type = 'merchant') = (merchant_id IS NOT NULL))
);

-- The one system account: money in flight from the card network.
INSERT INTO accounts (type) VALUES ('processor_clearing');

-- Every merchant registered before this migration gets their account now.
INSERT INTO accounts (merchant_id, type) SELECT id, 'merchant' FROM merchants;

-- One row per money movement (a capture or a refund). Each has exactly two entries below.
CREATE TABLE ledger_transactions (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  type          TEXT NOT NULL CHECK (type IN ('capture', 'refund')),
  reference_id  UUID NOT NULL,                  -- payment id (capture) or refund id (refund)
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  transaction_id  UUID NOT NULL REFERENCES ledger_transactions(id),
  account_id      UUID NOT NULL REFERENCES accounts(id),
  direction       TEXT NOT NULL CHECK (direction IN ('debit', 'credit')),
  amount          BIGINT NOT NULL CHECK (amount > 0),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ledger_entries_account_time_idx ON ledger_entries (account_id, created_at, id);
CREATE INDEX ledger_entries_transaction_idx ON ledger_entries (transaction_id);

-- Append-only: any UPDATE or DELETE on the ledger raises an error, no matter who runs it.
CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_append_only
  BEFORE UPDATE OR DELETE ON ledger_entries
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER ledger_transactions_append_only
  BEFORE UPDATE OR DELETE ON ledger_transactions
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
