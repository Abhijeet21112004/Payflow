CREATE TABLE merchants (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name            TEXT NOT NULL,
  email           TEXT NOT NULL UNIQUE,
  password_hash   TEXT NOT NULL,                 -- BCrypt
  api_key_hash    TEXT NOT NULL UNIQUE,          -- SHA-256 of the API key; the raw key is shown once and never stored
  api_key_prefix  TEXT NOT NULL,                 -- e.g. "pf_live_a1b2", lets the merchant identify which key this is
  webhook_secret  TEXT NOT NULL,                 -- plaintext because HMAC needs the original value (see SPEC §11)
  webhook_url     TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
