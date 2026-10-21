# Race condition: before and after

These are real outputs from `CaptureConcurrencyTest` and `RefundConcurrencyTest`, run against real PostgreSQL (Testcontainers).

To reproduce the "before" numbers yourself:

```bash
git checkout before-locking
cd payflow-api
./mvnw test -Dtest='*ConcurrencyTest'
```

## Before: no row lock (git tag `before-locking`)

Capture and refund read the payment with a plain `SELECT`, check its status in Java, then write.
Concurrent requests all read the same old state before any of them writes.

```
=== Capture race: 50 parallel captures of one ₹500.00 payment ===
successful captures  : 10   (expected 1)
rejected with 409    : 40   (expected 49)
ledger transactions  : 10   (expected 1)
merchant balance     : ₹5000.00   (expected ₹500.00)
```

One ₹500 payment credited the merchant ₹5,000. (10 is the size of the database connection pool, so every
request that got a connection in the first wave captured the payment.)

```
=== Refund race: 10 parallel ₹100.00 refunds of one ₹500.00 payment ===
successful refunds         : 10   (expected 5)
refunded per the ledger    : ₹1000.00   (expected ₹500.00)
refunded per the payment   : ₹100.00   (expected ₹500.00)
payment status             : partially_refunded   (expected refunded)
merchant balance           : ₹-500.00   (expected ₹0.00)
```

Two different kinds of corruption at once:
- **Over-refund:** ₹1,000 refunded on a ₹500 payment, because every request saw ₹0 already refunded.
- **Lost update:** the payment row says only ₹100 was refunded, because each request wrote `0 + 100` over the others.
  The ledger and the payment table now disagree.

## After: `SELECT … FOR UPDATE` + database backstops

The fix is one changed line in each of capture and refund: the payment is read with
`SELECT … FOR UPDATE`, which locks that row until the transaction commits. A second request for the
same payment waits at that line, then reads the row *after* the first one's changes.

```
=== Capture race: 50 parallel captures of one ₹500.00 payment ===
successful captures  : 1   (expected 1)
rejected with 409    : 49   (expected 49)
ledger transactions  : 1   (expected 1)
merchant balance     : ₹500.00   (expected ₹500.00)
```

```
=== Refund race: 10 parallel ₹100.00 refunds of one ₹500.00 payment ===
successful refunds         : 5   (expected 5)
refunded per the ledger    : ₹500.00   (expected ₹500.00)
refunded per the payment   : ₹500.00   (expected ₹500.00)
payment status             : refunded   (expected refunded)
merchant balance           : ₹0.00   (expected ₹0.00)
```

Two database constraints (migration `V4`) back the lock up, so even buggy code can't corrupt money
(proven by `SafetyConstraintsTest`):

| Constraint | Stops |
|---|---|
| `UNIQUE (type, reference_id)` on `ledger_transactions` | a payment being captured into the ledger twice |
| `CHECK (refunded_amount BETWEEN 0 AND amount)` on `payments` | refunding more than the payment's amount |

Only the *payment* row is locked, not the merchant's account, so payments of different customers
still run fully in parallel.
