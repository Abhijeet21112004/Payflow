CREATE TABLE payments (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  merchant_id      UUID NOT NULL REFERENCES merchants(id),
  amount           BIGINT NOT NULL CHECK (amount > 0),          -- paise (₹1 = 100)
  currency         TEXT NOT NULL DEFAULT 'INR',
  status           TEXT NOT NULL CHECK (status IN
                     ('created', 'authorized', 'captured', 'partially_refunded', 'refunded', 'failed')),
  refunded_amount  BIGINT NOT NULL DEFAULT 0,
  mock_outcome     TEXT NOT NULL CHECK (mock_outcome IN ('success', 'decline', 'timeout')),
  failure_reason   TEXT,                                        -- 'card_declined' | 'processor_timeout'
  customer_email   TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX payments_merchant_time_idx ON payments (merchant_id, created_at);

CREATE TABLE refunds (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_id   UUID NOT NULL REFERENCES payments(id),
  merchant_id  UUID NOT NULL REFERENCES merchants(id),
  amount       BIGINT NOT NULL CHECK (amount > 0),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX refunds_payment_idx ON refunds (payment_id);
