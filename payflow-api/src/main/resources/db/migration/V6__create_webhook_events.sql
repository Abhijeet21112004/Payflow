-- The webhook outbox. A row is written in the SAME transaction as the payment change it describes,
-- so an event exists if and only if the change committed. A background dispatcher sends them.
CREATE TABLE webhook_events (
  id                    UUID PRIMARY KEY,           -- also the event id sent to the merchant (for their de-duplication)
  merchant_id           UUID NOT NULL REFERENCES merchants(id),
  payment_id            UUID NOT NULL REFERENCES payments(id),
  event_type            TEXT NOT NULL CHECK (event_type IN ('payment.captured', 'payment.failed', 'payment.refunded')),
  payload               JSONB NOT NULL,
  status                TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'delivered', 'failed')),
  attempt_count         INT NOT NULL DEFAULT 0,
  next_attempt_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  locked_until          TIMESTAMPTZ,                -- lease: while set and in the future, one dispatcher owns this event
  last_error            TEXT,
  last_response_status  INT,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  delivered_at          TIMESTAMPTZ
);
CREATE INDEX webhook_events_due_idx ON webhook_events (status, next_attempt_at);
CREATE INDEX webhook_events_payment_idx ON webhook_events (payment_id);
