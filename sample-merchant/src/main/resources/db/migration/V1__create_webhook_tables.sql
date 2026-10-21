-- Every webhook event id this shop has already processed. The PRIMARY KEY is the de-duplication:
-- the database lets exactly one insert of a given event id succeed, no matter how many arrive at once.
CREATE TABLE processed_events (
  event_id     UUID PRIMARY KEY,
  event_type   TEXT NOT NULL,
  received_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The shop's "side effect": what it actually does when a payment event arrives.
-- If de-duplication works, there is exactly one row here per event.
CREATE TABLE fulfillment_log (
  id          BIGSERIAL PRIMARY KEY,
  event_id    UUID NOT NULL REFERENCES processed_events(event_id),
  payment_id  UUID NOT NULL,
  action      TEXT NOT NULL,           -- e.g. 'ship order', 'cancel order', 'issue store credit'
  amount      BIGINT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
