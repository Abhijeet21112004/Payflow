# PayFlow — Build Specification (v2)

> v2 replaces the original Node spec. Changes: Java/Spring stack, the concurrency fix targets the real race (payment status, not the account row), double-entry ledger, refunds modelled properly, idempotency on every money-moving endpoint, a transactional outbox for webhooks, and replay-safe webhook signatures.

---

## 1. What this is

PayFlow is a scoped-down payment gateway: the merchant-facing backend behind a "Pay Now" button, modelled on how Razorpay or Stripe work internally. A merchant registers, gets an API key, creates payments, captures them, refunds them, and receives webhooks when things happen.

The point is **not the feature list**. The point is a small set of operations that stay **provably correct** under concurrent requests, network retries, and crashes. Each phase exists to demonstrate one correctness property.

## 2. Correctness properties

| # | Property | Where it's enforced | How it's proven |
|---|---|---|---|
| P1 | **No double-capture or over-refund under concurrency.** N parallel captures of one payment move money exactly once; parallel partial refunds never exceed the captured amount. | `SELECT … FOR UPDATE` on the payment row + DB constraints as a backstop | `CaptureConcurrencyTest`, `RefundConcurrencyTest`, run before and after the fix |
| P2 | **Idempotent money-moving requests.** Retrying `POST /payments`, `/capture` or `/refund` with the same `Idempotency-Key` never repeats the side effect and returns the original response. | `idempotency_keys` reservation row with a unique constraint | `IdempotencyTest` (sequential + concurrent replays) |
| P3 | **All-or-nothing state changes.** A capture or refund either commits the status change, the ledger entries, the idempotency record and the webhook event together, or none of them. | One DB transaction per operation | Fault-injection test: throw mid-transaction, assert nothing changed |
| P4 | **Immutable, balanced audit trail.** Balances are never stored; they're derived from an append-only double-entry ledger where every transaction's debits equal its credits. | DB triggers block `UPDATE`/`DELETE`; double-entry invariant | `LedgerInvariantTest` |
| P5 | **Webhooks are never lost and never double-processed.** PayFlow guarantees at-least-once delivery; the receiver guarantees exactly-once processing. | Transactional outbox (sender) + `INSERT … ON CONFLICT DO NOTHING` dedup (receiver) | `WebhookDeliveryTest`, `ReceiverDedupTest` |

**Rule:** if an implementation choice would trade away one of these for convenience, don't make that trade. Flag it instead.

## 3. Tech stack

| Layer | Choice | Notes |
|---|---|---|
| Language | Java 25 (LTS) | |
| Framework | Spring Boot (latest stable 3.x/4.x), Spring Web | |
| Build | Maven (via `mvnw` wrapper) | |
| Database | PostgreSQL 17 | Runs in Docker |
| DB access | `JdbcTemplate` / `NamedParameterJdbcTemplate`, **raw SQL, no JPA/Hibernate** | Keeps every lock and transaction visible |
| Transactions | `@Transactional` at the service layer, isolation `READ COMMITTED` (Postgres default) + explicit row locks | See §6.1 for why not `SERIALIZABLE` |
| Migrations | Flyway (`V1__…sql`, `V2__…sql`) | |
| Auth | Hand-written servlet filters: JWT for `/merchants/me/**`, API key for `/payments/**` | No full Spring Security; only `spring-security-crypto` for BCrypt |
| JWT | `jjwt`, HS256, 1h expiry | |
| Webhook signing | `javax.crypto.Mac` HMAC-SHA256 | |
| HTTP client | `java.net.http.HttpClient` | |
| Scheduling | `@Scheduled` webhook dispatcher | |
| Tests | JUnit 5, Testcontainers (real Postgres), `ExecutorService` + `CountDownLatch` for concurrency | |
| Infra | Docker, Docker Compose, GitHub Actions CI, Render/Railway deploy | |
| Deliberately **not** used | JPA/Hibernate, Lombok, Spring Security (full), Kafka, Redis, microservices | Each hides mechanics or adds surface area without serving P1–P5 |

## 4. Repository layout

```
PayFlow/
├── docs/
│   └── SPEC.md                  ← this file
├── payflow-api/                 ← the gateway (Spring Boot)
│   └── src/main/java/com/payflow/
│       ├── merchant/            registration, login, webhook URL
│       ├── auth/                JWT filter, API-key filter
│       ├── ledger/              double-entry ledger, balance, history
│       ├── payment/             payments, simulator, capture, refund
│       ├── idempotency/         key reservation + replay
│       ├── webhook/             outbox, dispatcher, signing
│       └── common/              errors, money, JSON helpers
│   └── src/main/resources/db/migration/   ← Flyway SQL files
├── sample-merchant/             ← minimal Spring Boot webhook receiver
├── load-test/                   ← (optional) k6 scripts
├── docker-compose.yml
├── .github/workflows/ci.yml
└── README.md                    ← the design doc / interview story
```

Package-by-feature: each folder is one concept you can explain on its own.

## 5. Data model

All money is `BIGINT` in **paise** (₹1 = 100). Never floats. Only `INR` is supported in v1; other currencies are rejected with `400`.

```sql
-- V1: merchants
CREATE TABLE merchants (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name            TEXT NOT NULL,
  email           TEXT NOT NULL UNIQUE,
  password_hash   TEXT NOT NULL,                 -- BCrypt
  api_key_hash    TEXT NOT NULL UNIQUE,          -- SHA-256 of the API key; raw key shown once
  api_key_prefix  TEXT NOT NULL,                 -- e.g. "pf_live_a1b2" so the merchant can identify it
  webhook_secret  TEXT NOT NULL,                 -- needed in plaintext to compute HMACs (see §11 limitations)
  webhook_url     TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- V2: ledger (double-entry)
CREATE TABLE accounts (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  merchant_id  UUID UNIQUE REFERENCES merchants(id),   -- NULL for system accounts
  type         TEXT NOT NULL CHECK (type IN ('merchant', 'processor_clearing')),
  currency     TEXT NOT NULL DEFAULT 'INR',
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((type = 'merchant') = (merchant_id IS NOT NULL))
);
-- Seeded once by migration: a single 'processor_clearing' account representing
-- money in flight from the card network.

CREATE TABLE ledger_transactions (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  type          TEXT NOT NULL CHECK (type IN ('capture', 'refund')),
  reference_id  UUID NOT NULL,                  -- payment id (capture) or refund id (refund)
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (type, reference_id)                   -- backstop: a payment can be captured into the ledger only once
);

CREATE TABLE ledger_entries (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  transaction_id  UUID NOT NULL REFERENCES ledger_transactions(id),
  account_id      UUID NOT NULL REFERENCES accounts(id),
  direction       TEXT NOT NULL CHECK (direction IN ('debit', 'credit')),
  amount          BIGINT NOT NULL CHECK (amount > 0),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON ledger_entries (account_id, created_at, id);

-- Append-only enforcement: any UPDATE or DELETE raises an error.
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

-- V3: payments + refunds
CREATE TABLE payments (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  merchant_id      UUID NOT NULL REFERENCES merchants(id),
  amount           BIGINT NOT NULL CHECK (amount > 0),
  currency         TEXT NOT NULL DEFAULT 'INR',
  status           TEXT NOT NULL CHECK (status IN
                     ('created','authorized','captured','partially_refunded','refunded','failed')),
  refunded_amount  BIGINT NOT NULL DEFAULT 0,
  mock_outcome     TEXT NOT NULL CHECK (mock_outcome IN ('success','decline','timeout')),
  failure_reason   TEXT,                        -- 'card_declined' | 'processor_timeout'
  customer_email   TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (refunded_amount >= 0 AND refunded_amount <= amount)   -- backstop against over-refund
);
CREATE INDEX ON payments (merchant_id, created_at);

CREATE TABLE refunds (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_id   UUID NOT NULL REFERENCES payments(id),
  merchant_id  UUID NOT NULL REFERENCES merchants(id),
  amount       BIGINT NOT NULL CHECK (amount > 0),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON refunds (payment_id);

-- V4: idempotency
CREATE TABLE idempotency_keys (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  merchant_id      UUID NOT NULL REFERENCES merchants(id),
  idempotency_key  TEXT NOT NULL,
  request_hash     TEXT NOT NULL,     -- SHA-256 of method + path + canonical body
  status           TEXT NOT NULL CHECK (status IN ('in_progress', 'completed')),
  response_status  INT,
  response_body    JSONB,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at     TIMESTAMPTZ,
  UNIQUE (merchant_id, idempotency_key)
);

-- V5: webhook outbox
CREATE TABLE webhook_events (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),   -- also the event_id sent to the merchant
  merchant_id           UUID NOT NULL REFERENCES merchants(id),
  payment_id            UUID NOT NULL REFERENCES payments(id),
  event_type            TEXT NOT NULL CHECK (event_type IN
                          ('payment.captured','payment.failed','payment.refunded')),
  payload               JSONB NOT NULL,
  status                TEXT NOT NULL DEFAULT 'pending'
                          CHECK (status IN ('pending','delivered','failed')),
  attempt_count         INT NOT NULL DEFAULT 0,
  next_attempt_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  locked_until          TIMESTAMPTZ,          -- lease so two dispatchers never send the same event concurrently
  last_error            TEXT,
  last_response_status  INT,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  delivered_at          TIMESTAMPTZ
);
CREATE INDEX ON webhook_events (status, next_attempt_at);
```

### Double-entry, in one paragraph
Every movement of money is a `ledger_transaction` with **two entries whose amounts balance**:

| Event | Debit | Credit |
|---|---|---|
| Capture ₹500 | `processor_clearing` 50000 | `merchant` 50000 |
| Refund ₹200 | `merchant` 20000 | `processor_clearing` 20000 |

Merchant balance = `SUM(credits) − SUM(debits)` on their account. Across the entire ledger, total debits always equal total credits. That invariant is checked by a test (and by the optional reconciliation job).

### Payment state machine
```
created ──success──▶ authorized ──capture──▶ captured ──refund(partial)──▶ partially_refunded
   │                                            │                              │
   ├──decline──▶ failed                         └──refund(full)──▶ refunded ◀──┘ refund(remaining)
   └──timeout──▶ failed
```
Any transition not drawn here is rejected with `409 invalid_state`.

## 6. How each property is implemented

### 6.1 P1: concurrency (the headline)

**The race.** A naive capture does:
```
1. SELECT status FROM payments WHERE id = ?        -- reads 'authorized'
2. if status != 'authorized' → 409
3. INSERT ledger transaction + entries             -- credits merchant
4. UPDATE payments SET status = 'captured'
```
Fire 50 of these in parallel. Many threads read `authorized` in step 1 before any of them reaches step 4, and each one credits the merchant. **One ₹500 payment credits ₹500 × k.** Refunds race the same way: ten parallel ₹100 refunds on a ₹500 payment can all read `refunded_amount = 0` and together refund ₹1,000.

**The fix.** Step 1 becomes:
```sql
SELECT * FROM payments WHERE id = ? AND merchant_id = ? FOR UPDATE
```
`FOR UPDATE` takes a row lock that lasts until the transaction commits. The second transaction blocks at step 1 until the first commits, then re-reads the row, sees `captured`, and gets `409`. Captures and refunds of the *same payment* queue up; operations on *different payments* run fully in parallel.

**Defense in depth.** Even if the lock were removed by mistake, the database refuses corruption:
- `UNIQUE (type, reference_id)` on `ledger_transactions` stops a second capture posting to the ledger.
- `CHECK (refunded_amount <= amount)` on `payments` stops over-refunds.

**Why lock the payment and not the merchant's account?** The invariant we protect ("captured once", "refunds ≤ captured") belongs to the payment. Locking the account would serialize *all* of a merchant's payments for no benefit, and locking the shared `processor_clearing` account would serialize the entire system. Merchant balances are allowed to go negative after refunds; real gateways net that against future settlements.

**Why `READ COMMITTED` + `FOR UPDATE`, not `SERIALIZABLE`?** `SERIALIZABLE` would also prevent the race, but Postgres enforces it by *aborting* one of the conflicting transactions with a serialization error, so every caller would need retry logic. An explicit row lock makes the conflict point obvious in the code and turns it into waiting instead of failing.

**Lock ordering / deadlocks.** Each operation locks exactly one payment row, so no two transactions can wait on each other in a cycle.

**Before/after proof.** Phase 3 ships the naive version, tagged `before-locking` in git. `CaptureConcurrencyTest` and `RefundConcurrencyTest` are run against it and the corrupted results are recorded. Phase 4 adds the lock and constraints; the same tests pass. Both results go in the README.

### 6.2 P2: idempotency

Required header `Idempotency-Key` (1–255 chars) on `POST /payments`, `POST /payments/{id}/capture`, `POST /payments/{id}/refund`. Missing → `400`.

`request_hash` = SHA-256 of `METHOD + " " + PATH + "\n" + canonical JSON body` (fields in fixed order). Including the path means reusing a key on a different endpoint is detected.

**Algorithm (capture and refund: one transaction):**
```
BEGIN
  INSERT INTO idempotency_keys (merchant_id, idempotency_key, request_hash, status)
  VALUES (?, ?, ?, 'in_progress')
  ON CONFLICT (merchant_id, idempotency_key) DO NOTHING
  RETURNING id;

  if a row was inserted:
      do the capture/refund (§6.1)
      UPDATE idempotency_keys SET status='completed', response_status=?, response_body=?, completed_at=now()
      COMMIT → return response
  else:
      SELECT the existing row
      hash differs          → 422 idempotency_key_reused
      status = completed    → return the stored response (replay)
      status = in_progress  → 409 request_in_progress
```
Why this is race-free: if two identical requests arrive together, the second `INSERT` **blocks on the unique index** until the first transaction commits. It then sees the conflict, finds a `completed` row, and replays it. If the first transaction rolls back, the second one's insert succeeds and it does the work itself.

**`POST /payments` uses two transactions,** because the simulator stands in for a network call to a card network, and **you never hold a DB transaction open across a network call**:
```
TX1: reserve key (in_progress) + INSERT payment (status 'created')   → COMMIT
     call simulator (may sleep for 'timeout')                        ← no transaction held
TX2: lock payment, set authorized/failed, insert webhook event if failed,
     mark key completed with the response                            → COMMIT
```
A duplicate request arriving between TX1 and TX2 gets `409 request_in_progress`; after TX2 it gets the replayed response.

Validation errors (`400`) are returned before a key is reserved, so they're never stored.

### 6.3 P3: all-or-nothing

`@Transactional` on `PaymentService.capture()` and `.refund()`. Inside one transaction: idempotency reservation → payment lock → ledger transaction + 2 entries → payment status/`refunded_amount` update → webhook event insert → idempotency completion. Any exception rolls back all of it.

Proof: a test uses a test-only hook to throw after the ledger insert, then asserts the payment status, the ledger, the outbox and the idempotency table are all unchanged.

### 6.4 P4: immutable, balanced ledger

- Triggers make `ledger_entries` and `ledger_transactions` reject `UPDATE`/`DELETE` (§5). Proof: a test attempts an `UPDATE` and asserts the exception.
- Balance query:
  ```sql
  SELECT COALESCE(SUM(CASE WHEN direction = 'credit' THEN amount ELSE -amount END), 0)
  FROM ledger_entries WHERE account_id = ?
  ```
- Invariant test: after a mixed workload, `SUM(debits) = SUM(credits)` globally and per transaction, and each merchant balance = captured − refunded.

### 6.5 P5: webhooks

**Outbox (sender side).** The `webhook_events` row is inserted **in the same transaction** as the state change it describes. If the transaction commits, the event exists; if it rolls back, it doesn't. Nothing is sent over HTTP inside that transaction.

**Dispatcher.** `@Scheduled(fixedDelay = 1s)`:
1. Claim a batch with a lease (short transaction):
   ```sql
   UPDATE webhook_events SET locked_until = now() + interval '30 seconds'
   WHERE id IN (
     SELECT id FROM webhook_events
     WHERE status = 'pending' AND next_attempt_at <= now()
       AND (locked_until IS NULL OR locked_until < now())
     ORDER BY next_attempt_at
     LIMIT 10
     FOR UPDATE SKIP LOCKED
   )
   RETURNING *;
   ```
   `SKIP LOCKED` lets several dispatcher instances run without sending the same event twice at the same time.
2. For each event, outside any transaction: POST to the merchant's `webhook_url` with a 5s timeout.
3. Record the result: 2xx → `delivered`. Otherwise `attempt_count++` and `next_attempt_at` = now + backoff (**5s, 30s, 2m, 10m, 1h**). After 6 failed attempts → `failed` (dead letter).

**Request format.**
```
POST <webhook_url>
Content-Type: application/json
X-PayFlow-Event-Id: <event id>
X-PayFlow-Timestamp: <unix seconds>
X-PayFlow-Signature: v1=<hex HMAC-SHA256(webhook_secret, timestamp + "." + raw_body)>

{"id":"<event id>","type":"payment.captured","created_at":"…",
 "data":{"payment_id":"…","amount":50000,"currency":"INR","status":"captured","refunded_amount":0}}
```
- The signature covers the **exact bytes sent**, and the receiver verifies against the **raw request body**, never a re-serialized object (re-serializing can reorder keys or change whitespace).
- The timestamp is part of the signed content, and the receiver rejects anything older than 5 minutes, so an attacker can't replay a captured webhook later.
- Signatures are compared in constant time (`MessageDigest.isEqual`) to avoid timing attacks.

**Receiver (`sample-merchant/`).** `POST /webhooks/payflow`:
1. Verify signature + timestamp → `401` if invalid.
2. In one transaction:
   ```sql
   INSERT INTO processed_events (event_id, received_at) VALUES (?, now())
   ON CONFLICT (event_id) DO NOTHING;
   ```
   - 0 rows inserted → duplicate: commit, return `200` without side effects.
   - 1 row inserted → apply the side effect (e.g. `UPDATE orders SET status = 'paid'`) in the **same transaction**, commit, return `200`.

   Because the dedup record and the side effect commit together, a crash can never leave "marked processed but not fulfilled" or the reverse. Two concurrent deliveries of the same event serialize on the primary key, and exactly one of them wins.

## 7. API

Errors always look like: `{"error": {"code": "invalid_state", "message": "Payment is not in 'authorized' state"}}`

### Merchant management (JWT: `Authorization: Bearer <jwt>`)
| Method & path | Body | Response |
|---|---|---|
| `POST /merchants/register` (no auth) | `{name, email, password}` | `201 {merchant_id, api_key, webhook_secret}`, where the key and secret are **shown once** |
| `POST /merchants/login` (no auth) | `{email, password}` | `200 {token, expires_in}`; bad credentials → `401` with a generic message |
| `PUT /merchants/me/webhook-url` | `{webhook_url}` | `200`; must be an http(s) URL |
| `GET /merchants/me/balance` | | `200 {balance, currency}` |
| `GET /merchants/me/ledger?limit=20&cursor=…` | | `200 {entries[], next_cursor}` with **keyset pagination** on `(created_at, id)`, so pages stay fast and stable while new rows arrive |

### Payments (API key: `Authorization: Bearer pf_live_…`)
| Method & path | Body | Response |
|---|---|---|
| `POST /payments` + `Idempotency-Key` | `{amount, currency, customer_email, mock_outcome}` | `201` payment, with status `authorized` or `failed` |
| `POST /payments/{id}/capture` + `Idempotency-Key` | none (full capture only) | `200` payment; wrong state → `409` |
| `POST /payments/{id}/refund` + `Idempotency-Key` | `{amount?}`, where omitting it refunds the remainder | `201` refund; exceeds remaining → `422`; wrong state → `409` |
| `GET /payments/{id}` | | `200` payment |

A payment belonging to another merchant returns `404`, not `403`, so the API doesn't reveal that it exists.

API keys are looked up by `SHA-256(key)`; the raw key is never stored.

## 8. Mock simulator

`mock_outcome` replaces the card network:
- `success` → `authorized`
- `decline` → `failed`, `failure_reason = 'card_declined'`, emits `payment.failed`
- `timeout` → sleeps `payflow.simulator.timeout-delay` (default 3s), then `failed`, `failure_reason = 'processor_timeout'`, emits `payment.failed`. This demonstrates that no transaction or lock is held during slow external calls.

## 9. Tests (the proof)

All run against real Postgres via Testcontainers, in CI on every push.

| Test | Asserts |
|---|---|
| `CaptureConcurrencyTest` | 50 threads capture one payment (different keys) → exactly 1 × `200`, 49 × `409`; balance = amount; 1 ledger transaction |
| `RefundConcurrencyTest` | ₹500 payment, 10 threads refund ₹100 each → exactly 5 succeed; `refunded_amount = 50000`; status `refunded` |
| `IdempotencyTest` | sequential replay returns an identical response and creates 1 payment; 20 concurrent same-key requests → 1 payment; same key with a different body → `422` |
| `AtomicityTest` | injected failure mid-capture → nothing changed anywhere |
| `LedgerInvariantTest` | debits = credits; balances match; `UPDATE ledger_entries` throws |
| `WebhookDeliveryTest` | outbox row created with the state change; failing endpoint → retries with backoff; succeeds later → `delivered`; signature verifies |
| `ReceiverDedupTest` (sample-merchant) | same event delivered twice, sequentially and concurrently → side effect happens once; bad signature → `401`; old timestamp → `401` |

Concurrency tests use `CountDownLatch` so all threads start at the same instant, which maximizes contention.

## 10. Build order

⭐ = you write this part yourself (with guidance). These are the pieces interviewers probe hardest.

| Phase | Deliverable | Concepts you'll be able to explain |
|---|---|---|
| **0. Setup** | Docker Desktop, IntelliJ, Git repo on GitHub, Spring Boot project, Postgres in Docker Compose, Flyway, `/health` | What Spring Boot does, what a migration is, why Docker |
| **1. Merchants & auth** | register, login, JWT filter, API-key filter | Hashing vs encryption, BCrypt, JWT structure, why API keys are hashed |
| **2. Ledger** | accounts, double-entry posting, balance, keyset-paginated history, append-only triggers | Double-entry, derived vs stored state, indexes, pagination |
| **3. Payments (naive)** | payments, simulator, capture, refund **without locks** → tag `before-locking`, run the concurrency tests, record the corruption | State machines, REST design, what a race condition looks like in a DB |
| **4. The fix** ⭐ | `FOR UPDATE`, `UNIQUE`/`CHECK` backstops; tests go green | Row locks, isolation levels, why not `SERIALIZABLE`, deadlocks |
| **5. Idempotency** ⭐ | reservation algorithm, replay, 409/422 cases | Unique-index blocking, retries, why two transactions for `/payments` |
| **6. Webhooks** | outbox, dispatcher, lease + `SKIP LOCKED`, backoff, HMAC | Outbox pattern, at-least-once delivery, signing, replay attacks |
| **7. Receiver** ⭐ | `sample-merchant` with signature check + dedup | Exactly-once processing, constant-time comparison |
| **8. Ship it** | CI on GitHub Actions, Dockerfile, deploy | CI/CD, containers |
| **9. Tell the story** | README as a design doc, a one-page defense cheat sheet, mock interview | |

**Optional extras** (only if time allows, in this order): reconciliation job (re-derives every balance and checks debits = credits) → k6 load test with throughput numbers → sweeper for payments stuck in `created` → deferred constraint trigger that enforces balanced transactions inside the database.

## 11. Known limitations (documented on purpose; interviewers like hearing these)

- `webhook_secret` is stored in plaintext because HMAC needs the original value. In production: encrypt at rest with a KMS.
- Webhook URLs aren't checked for SSRF (they may point at localhost for the demo). In production: block private/internal IP ranges.
- If the process crashes between TX1 and TX2 of `POST /payments`, the payment stays `created` and its key stays `in_progress`. The optional sweeper handles this; otherwise the client retries with a new key.
- Single currency, full capture only, no settlement to bank accounts.
- No rate limiting.

## 12. Out of scope

A dashboard/UI, KYC and onboarding compliance, real settlement, disputes/chargebacks, real card/UPI integration, multi-currency, partial capture.
