-- One row per (merchant, Idempotency-Key). The UNIQUE constraint is what makes retries safe:
-- two requests with the same key can never both reserve it.
CREATE TABLE idempotency_keys (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  merchant_id      UUID NOT NULL REFERENCES merchants(id),
  idempotency_key  TEXT NOT NULL,
  request_hash     TEXT NOT NULL,     -- SHA-256 of method + path + body, to catch a key reused for a different request
  status           TEXT NOT NULL CHECK (status IN ('in_progress', 'completed')),
  response_status  INT,
  response_body    TEXT,              -- stored as the exact text sent, so a replay is byte-for-byte identical
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at     TIMESTAMPTZ,
  UNIQUE (merchant_id, idempotency_key)
);
