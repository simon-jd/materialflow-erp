# ADR 0008: Inventory reservation under hot-SKU contention

Date: 2026-11-24. Status: accepted. Refines [ADR 0003](0003-consistency-locking-idempotency.md).
Measurements: [evidence README](../evidence/local-2026-11-23-inventory-concurrency/README.md).

## Context

An order reserves stock inside one PostgreSQL transaction: idempotency record,
credit row lock (`SELECT ... FOR UPDATE`), order and lines, then one inventory row
lock per line in a stable key order, a version-checked `UPDATE`, a reservation row,
a RESERVE ledger row, line/status updates, a procurement suggestion for any
shortage, an outbox event and the idempotency completion. Partial fulfillment is a
per-order choice: shortage backorders or partially allocates; it is not a rejection.

ADR 0003 chose explicit row locks "over optimistic maximum throughput" without
numbers. Popular SKUs make one inventory row the serialization point, so the
choice needed measuring against the two usual alternatives.

## Options measured

A standalone harness (PostgreSQL 17.11, `fsync=on`, 20-connection HikariCP, 3 s
acquisition timeout, closed-loop virtual-thread workers) reserves 1–5 units per
request, all-or-nothing, with the ledger row in the same transaction. Stock covers
half of the demand, so every run crosses the sold-out boundary.

- **Pessimistic** (production): `SELECT ... FOR UPDATE`, decide in Java, version-checked `UPDATE`.
- **Optimistic**: unlocked read, `UPDATE ... WHERE version = ?`, at most 10 attempts with
  full-jitter backoff from 2 ms to a 100 ms cap.
- **Conditional**: `UPDATE ... SET reserved = reserved + :q WHERE id = :id AND
  on_hand - reserved >= :q RETURNING reserved`.
- An unguarded read-modify-write control, which oversold in all 48 of its runs and
  shows that the invariant check can fail.

## Results

Two full harness invocations, each with three measured repetitions per cell
(192 measured runs in total). Medians, attempts per second:

| Hot SKU, workers | Pessimistic | Optimistic (requests exhausted) | Conditional |
|---:|---:|---:|---:|
| 8 | 1,361–1,505 | 1,918–2,232 (0.9–1.1%) | 2,095–2,600 |
| 32 | 1,756–2,035 | 856–1,264 (6.0–7.3%) | 2,666–3,163 |
| 64 | 1,224–1,739 | 430–916 (12.9–14.5%) | 1,597–3,108 |
| 128 | 764–2,126 | 295–914 (10.0–15.3%) | 1,011–3,109 |

Ranges span the two invocations; the first ran while other agents pushed the host
load average to 180. No guarded strategy oversold or lost an update in any run. On one hot SKU the conditional statement
was 1.3–1.9× faster than pessimistic locking at every worker count. Optimistic
retries fell to 0.35–0.62× of pessimistic throughput from 32 workers on, with
6–15% of requests ending in exhausted retries rather than a reservation or a
stock answer; even at 8 workers its p99 was 6–8× higher. Spread over 100 SKUs the
three stayed within 1.35× of each other and optimistic retried 0.03–0.08 times per
request with none exhausted. From 64 workers on, every strategy's p99 rose to
0.4–1.9 s in the quieter invocation while p50 stayed in single-digit milliseconds,
which is consistent with queueing for the 20 pooled connections (pool wait was not
measured separately).

End to end, 100 simultaneous order submissions per wave for one SKU whose 1,000
units cover 143 of 450 orders (plus 50 concurrent idempotency-key reuses) kept
every invariant in all six measured runs: reserved = on hand = line allocations =
active reservations = RESERVE ledger, exactly 142 reserved / 1 partial / 307
backordered orders, one order, idempotency record and outbox event per key, and
no deadlocks. Straight to erp-core the median p50/p95/p99 was 277/508/559 ms at
153–251 requests/s; through the JWT gateway, paced to its per-principal rate limit,
373/975/1,102 ms. The direct path spent 4.4–7.3 ms of serialized time per order on
the hot row, against 0.47–0.73 ms per bare pessimistic reservation in the harness.
The order transaction runs about ten more statements while it holds the row, and
PostgreSQL showed 18 backends waiting on a lock mid-burst while erp-core's CPU was
far from its limit.

## Trade-offs

**Lock wait (pessimistic).** Every request for the SKU queues on the row lock, held
from the `SELECT` to commit, including requests that will be told "sold out". In
exchange the decision runs on a value nobody else can change, it needs no retry
loop, and the same code handles partial allocation, multi-line orders and fencing
tokens in the `InventoryBalance` domain model. With more workers than connections,
the wait moves into the connection pool, which every strategy shares.

**Retry storms (optimistic).** Under READ COMMITTED a version-checked `UPDATE` that
meets an uncommitted writer still waits for that writer's row lock, then re-checks
the version and fails. A loser therefore pays the lock wait *and* a retry, and
backoff only spreads the storm out. On a hot row with 32 or more workers this
produced the lowest throughput, the longest tails and requests that ended with
neither a reservation nor a stock answer. It does well only when rows rarely collide, as on 100 SKUs.

**Single statement (conditional).** It has the shortest critical section and needs
no retries because PostgreSQL re-evaluates the predicate after a blocked update.
Its limits come from everything else the order transaction must do on the same row:

- The row lock is held from the `UPDATE` to commit in every strategy. The ledger
  and reservation rows can use `RETURNING`, but the rest of the order transaction
  (line and status updates, procurement suggestion, response re-read, audit, outbox,
  idempotency) still runs under the lock. Removing the `SELECT` round trip shortens that window only a little.
- Partial allocation needs `LEAST(:q, on_hand - reserved)` and the amount actually
  allocated; PostgreSQL 17 cannot return pre-update values (`RETURNING OLD` arrives
  in 18), so this needs a CTE or a second read.
- All-or-nothing across several lines needs every row checked before any is
  changed: conditional updates per line with rollback on the first miss still hold
  earlier rows' locks and still need the stable lock order.
- The invariant moves from the tested domain model into SQL, and the fencing token
  becomes whatever `RETURNING version` reports.

## Decision

Keep pessimistic locking for order allocation. All three guarded strategies kept
the invariant, so the choice is about throughput, tails and fit: the conditional
statement wins the isolated benchmark, but in the real order transaction it would
remove one of roughly a dozen round trips made while the row is locked, and it
cannot express partial or multi-line allocation without giving back most of its
simplicity. Do not use optimistic retries on inventory rows; reconsider them only
for rarely contended rows. Keep the version check under the lock: it costs one
predicate, and it is what turned the bug below into a 409 instead of a lost update.

Recommendation, not adopted in this change: if hot-SKU throughput becomes the
bottleneck, first shorten what runs while the inventory row is locked. Building
the response from in-memory state instead of re-reading the order removes two of
those round trips, and batching the reservation, ledger and line writes removes
more. Only then consider the conditional `UPDATE` for single-line, all-or-nothing
orders, keeping the locking path for partial and multi-line orders.

## Correctness finding

Reading the path for this ADR exposed a defect unrelated to concurrency: an order
with two lines on the same warehouse/material/location built both lines from one
locked snapshot. The second line's version-checked update ran against the version
the first line had just written, so a valid order failed with 409
`INVENTORY_CONCURRENT_UPDATE`, and the all-or-nothing check compared each line
rather than their sum. Allocation now sums demand per balance and carries each
balance's new state and version from line to line. A regression test failed on the
old code and passes now.

## Consequences

`OrderConcurrencyIntegrationTest` now races 48 orders from 12 customers (orders
from one customer serialize on its credit row first, so the old single-customer
test never contended on inventory) with 8 concurrent idempotency-key reuses, and
reconciles balance, allocations, reservations, ledger, statuses, shortages and
credit. The harness and the HTTP test are repeatable commands in
`tests/performance/README.md`. Numbers come from one shared laptop with other
workloads running and are evidence for relative behavior, not capacity planning.
