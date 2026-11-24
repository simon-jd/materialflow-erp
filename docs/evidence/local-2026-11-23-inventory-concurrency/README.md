# Inventory reservation concurrency — November 23–24, 2026

Synthetic data, local execution only. This evidence backs
[ADR 0008](../../adr/0008-inventory-reservation-concurrency.md). Labels: **measured**
values come straight from a run; **derived** values are computed from measured ones
and the formula is given; nothing here is an estimate. Large raw files (JTLs,
JMeter logs, per-run host samples, full per-run JSON) stay under the Git-ignored
`raw/` folder.

## Environment

Apple Silicon MacBook (arm64, 12 CPUs, 36 GiB), macOS Darwin 27.0.0, Docker Desktop
29.8.0 with 12 CPUs / 12.5 GB, OpenJDK 21.0.13.1, PostgreSQL 17.11
(`postgres:17.11-alpine`, `fsync=on`, `synchronous_commit=on`, READ COMMITTED,
`max_connections=100`), pgjdbc 42.7.12, HikariCP 7.0.2, Testcontainers 2.0.5,
Apache JMeter 5.6.3 (official archive, SHA-512 verified). Three other agents shared
the host and Docker VM throughout; their containers and the 1-minute load average
were sampled during every measurement ([environment.json](environment.json)). The
heavy-Docker lock was held for every measurement below, but it does not cover the
other agents' Testcontainers builds. Treat the numbers as relative evidence from a
noisy shared laptop, not as capacity figures.

## 1. Strategy comparison harness

`ReservationContentionHarness` ([tests/performance/README.md](../../../tests/performance/README.md))
on a disposable PostgreSQL. 4,000 requests per run, 1–5 units each, all-or-nothing,
ledger row in the same transaction; each SKU's stock is half of its demand. Virtual-
thread workers in a closed loop share a 20-connection pool with a 3 s acquisition
timeout (erp-core's settings). Per (workload, workers, strategy) cell: one warm-up
and three measured repetitions on identical seeded demand, strategy order rotated.
The whole command ran twice:

| Invocation | UTC | Host load average (1 min) | Files |
|---|---|---|---|
| harness-1 | 08:06–08:13 | 14 → 180 (other agents' builds peaked during the 64/128-worker hot-SKU cells) | [summary](harness-1-summary.csv), [per run](harness-1-runs.csv), [JSON](harness-1-summary.json) |
| harness-2 | 08:46–08:50 | 8.7 – 11.4 | [summary](harness-2-summary.csv), [per run](harness-2-runs.csv), [JSON](harness-2-summary.json) |

### Invariant (measured, 192 measured runs + 64 warm-ups)

| Strategy | Runs violating the invariant | Oversold units |
|---|---:|---:|
| pessimistic | 0 / 48 | 0 |
| optimistic | 0 / 48 | 0 |
| conditional | 0 / 48 | 0 |
| unguarded control | 48 / 48 | 17,093–18,004 per hot-SKU cell; 1,077–2,878 per 100-SKU cell |

The invariant is: units acknowledged to clients = stored `reserved` = ledger total,
ledger rows = successful requests, and `reserved <= on_hand` for every SKU. Warm-ups
are checked as well, and the guarded strategies passed them too. The harness table
deliberately has no CHECK constraint, so zero oversell is the strategy's doing. The
control acknowledged nearly every hot-SKU request while lost updates kept
`reserved` low, so it promised about twice the stock in each run.

### Hot SKU, medians of 3 repetitions (measured)

Attempts/s counts every request, including "sold out" answers; the p50/p95/p99
include waiting for a pooled connection. "Exhausted" are optimistic requests that
lost 10 version races and got neither stock nor a stock answer.

| Workers | Strategy | harness-1 attempts/s | harness-2 attempts/s | harness-2 p50 / p95 / p99 ms | Exhausted (h1 / h2, of 12,000) | Pool timeouts (h1 / h2) |
|---:|---|---:|---:|---|---:|---:|
| 8 | pessimistic | 1,505 | 1,361 | 3.5 / 14.9 / 22.1 | 0 / 0 | 0 / 0 |
| 8 | optimistic | 1,918 | 2,232 | 0.8 / 1.4 / 141.5 | 129 / 108 | 0 / 0 |
| 8 | conditional | 2,095 | 2,600 | 1.2 / 10.3 / 15.9 | 0 / 0 | 0 / 0 |
| 32 | pessimistic | 1,756 | 2,035 | 6.0 / 34.9 / 238.9 | 0 / 0 | 0 / 0 |
| 32 | optimistic | 856 | 1,264 | 1.3 / 226.3 / 314.1 | 878 / 716 | 0 / 0 |
| 32 | conditional | 2,666 | 3,163 | 3.6 / 26.7 / 114.7 | 0 / 0 | 1 / 0 |
| 64 | pessimistic | 1,224 | 1,739 | 8.0 / 50.2 / 923.9 | 0 / 0 | 26 / 0 |
| 64 | optimistic | 430 | 916 | 8.6 / 325.9 / 394.8 | 1,742 / 1,548 | 0 / 0 |
| 64 | conditional | 1,597 | 3,108 | 4.1 / 33.9 / 558.9 | 0 / 0 | 0 / 0 |
| 128 | pessimistic | 764 | 2,126 | 5.6 / 54.8 / 1,850.6 | 0 / 0 | 238 / 0 |
| 128 | optimistic | 295 | 914 | 39.2 / 573.9 / 707.6 | 1,833 / 1,198 | 0 / 0 |
| 128 | conditional | 1,011 | 3,109 | 4.6 / 73.2 / 1,187.4 | 0 / 0 | 355 / 0 |

Derived from the table:

- Conditional / pessimistic throughput: 1.30–1.52× in harness-1 and 1.46–1.91× in
  harness-2, at every worker count.
- Optimistic / pessimistic at 32–128 workers: 0.35–0.49× (harness-1) and 0.43–0.62×
  (harness-2); 6.0–15.3% of its requests ended exhausted. At 8 workers it was faster
  than pessimistic but still exhausted 0.9–1.1% of requests and had a 6–8× higher p99.
- Pessimistic critical section ≈ 1 / throughput = 0.47–0.73 ms per request in
  harness-2 (every pessimistic request, sold out or not, takes the row lock).
- For pessimistic and conditional at 64–128 workers, p50 stays at 4–8 ms while p99
  reaches 0.56–1.85 s, which points at queueing for the 20 pooled connections
  rather than at the strategy; pool wait was not measured separately. In harness-1 the same cells
  hit the 3 s acquisition timeout (errors recorded as SQLState `null`; harness-2
  labels them `SQLTransientConnectionException`).

### 100 SKUs, medians of 3 repetitions (harness-2, measured)

| Workers | pessimistic attempts/s | optimistic attempts/s (retries) | conditional attempts/s | p99 ms (pess / opt / cond) |
|---:|---:|---:|---:|---|
| 8 | 5,087 | 5,444 (360) | 6,510 | 3.6 / 5.8 / 2.9 |
| 32 | 8,342 | 8,479 (1,006) | 10,680 | 21.8 / 24.1 / 19.2 |
| 64 | 6,665 | 7,765 (953) | 8,978 | 151.3 / 88.9 / 79.3 |
| 128 | 8,780 | 8,966 (950) | 8,733 | 180.8 / 146.1 / 260.7 |

Spread over 100 SKUs the three guarded strategies stay within 1.35× of each other,
optimistic needs 0.03–0.08 retries per request and none are exhausted. harness-1
also had no exhausted retries on 100 SKUs, at lower and noisier rates.

## 2. Hot SKU through the HTTP API

The qualification stack (`deploy/compose/compose.yml` + `qualification.yml`,
project `mf-inventory-concurrency`, image tag `inventory-concurrency`, ports moved
from 2xxxx to 3xxxx, 2 CPU / 1 GiB per app, JWT on) ran images built from the JARs
of the passing `-Pquality verify`; the running erp-core and gateway JAR hashes
match. `tests/security/jwt-rbac-e2e.sh` passed against it first (401/201/403/404).

Each run: a fresh SKU with 1,000 units, 50 synthetic customers, 5 waves of 100
simultaneous `POST /api/v1/orders` of 7 units with partial fulfillment allowed and
a JWT for `synthetic-demo` from the `materialflow-cli` client. The last 10 threads
of each wave repeat the Idempotency-Key and body of the first 10 concurrently, so
500 requests carry 450 keys. One unmeasured warm-up run precedes three measured
runs. Expected outcome: 142 orders RESERVED (994 units), 1 PARTIALLY_ALLOCATED
(6 units), 307 BACKORDERED with procurement suggestions.

| Path | Run | Requests (non-201) | p50 / p95 / p99 ms | Throughput, requests/s |
|---|---:|---|---|---:|
| Gateway, 2 s between waves | 1 | 500 (0) | 372 / 975 / 1,102 | 149.1 burst, 43.9 overall |
| | 2 | 500 (0) | 373 / 772 / 943 | 149.7 burst, 44.0 overall |
| | 3 | 500 (0) | 671 / 1,129 / 1,214 | 91.7 burst, 37.1 overall |
| erp-core direct, no pauses | 1 | 500 (0) | 340 / 576 / 674 | 152.8 |
| | 2 | 500 (0) | 220 / 370 / 410 | 251.0 |
| | 3 | 500 (0) | 277 / 508 / 559 | 190.1 |

Medians of the per-run percentiles: gateway 373 / 975 / 1,102 ms, erp-core 277 /
508 / 559 ms; aggregate erp-core throughput 190.0 requests/s
([gateway](http-gateway-summary.json), [erp-core](http-core-summary.json)). Burst
throughput divides requests by the summed wave durations, excluding the pauses
that keep one principal within the gateway's 50 requests/s, burst 100 rate limit.
The direct path sends the same JWT to erp-core's own resource server.

**SQL invariants, all 6 measured runs (measured):** on hand 1,000, reserved 1,000;
reserved = Σ line allocations = Σ active reservations = Σ RESERVE ledger; 143
reservation rows and 143 ledger rows for 143 allocated lines; statuses exactly
142 / 1 / 307 and consistent with allocations; 2,150 units of procurement
shortage = ordered − allocated; 450 orders = 450 completed idempotency records =
450 outbox events; credit RESERVE ledger = order totals (31,500.00). PostgreSQL
reported 0 deadlocks after all HTTP runs ([gateway](http-gateway-invariants.json),
[erp-core](http-core-invariants.json)).

**Gateway shedding (measured).** The gateway fails closed with 503
`SHARED_RATE_LIMITER_UNAVAILABLE` when its Redis probe exceeds 250 ms (ADR 0004).
A first trial on the cold stack lost 49 of its first 100-request wave this way, and
the gateway warm-up lost 8 of 500. Those requests created no orders: in both cases
orders = completed idempotency keys and every other check held. The three measured
gateway runs had none. This is why the script now starts with a warm-up run.

**Where the time goes (derived).** On the direct path the single SKU took 4.4–7.3 ms
of serialized work per order (run duration / 450 orders; duplicate replays do not
lock inventory), against 0.47–0.73 ms per bare pessimistic reservation in
harness-2, roughly 6–15× longer. A mid-burst sample showed 18 PostgreSQL backends
waiting on a lock (erp-core's pool holds at most 20) while erp-core used 19–55% of
one CPU against a 2-CPU limit, so the hot row, not CPU, was the bottleneck. After
the inventory update the order transaction runs about ten more statements while
the row stays locked: reservation, ledger, line update, procurement suggestion,
status update, two response re-reads, audit log, outbox and idempotency completion.

## 3. CI regression test

`OrderConcurrencyIntegrationTest` (Testcontainers, `postgres:17.11-alpine`):

- 48 orders from 12 customers race for 250 units with 8 concurrent idempotency-key
  reuses: 48 distinct orders, 35 RESERVED / 1 PARTIALLY_ALLOCATED / 12 BACKORDERED,
  reserved = allocations = active reservations = RESERVE ledger = 250, 36 ledger
  rows, 86 units of shortage, credit = 48 × 70.00.
- Two lines on one balance: 6 + 9 reserve both; 4 + 4 against 5 remaining backorders
  all-or-nothing; 3 + 3 partially allocates 3 + 2; fencing tokens 1–4. Against the
  previous `allocate()` this test failed with `INVENTORY_CONCURRENT_UPDATE`.

`mvn -B -ntp -Pquality verify`: 58 JUnit tests in 21 suites, 0 failures, errors or
skips; Checkstyle and SpotBugs passed. `python3 -m unittest discover -s
tests/performance -p 'test_*.py'`: 4 tests passed.

## Reproduction

```bash
export JAVA_HOME=/path/to/jdk-21
bash tests/performance/run-reservation-contention.sh /tmp/mf-harness-NEW \
  --requests=4000 --repetitions=3 --workers=8,32,64,128

mvn -B -ntp -Pquality verify
bash tests/deployment/build-runtime-images.sh inventory-concurrency
sed -e 's/^COMPOSE_PROJECT_NAME=.*/COMPOSE_PROJECT_NAME=mf-inventory-concurrency/' \
    -e 's/=2\([0-9]\{4\}\)$/=3\1/' deploy/compose/qualification.env >/tmp/mf-ic.env
QUALIFICATION_TAG=inventory-concurrency docker compose --env-file /tmp/mf-ic.env \
  -p mf-inventory-concurrency -f deploy/compose/compose.yml \
  -f deploy/compose/qualification.yml up -d --no-build
set -a; source /tmp/mf-ic.env; set +a
export JMETER_HOME=/path/to/apache-jmeter-5.6.3
bash tests/performance/run-hot-sku-orders.sh /tmp/mf-http-gateway-NEW
MATERIALFLOW_HOT_SKU_TARGET=core MATERIALFLOW_HOT_SKU_PAUSE_MS=0 \
  bash tests/performance/run-hot-sku-orders.sh /tmp/mf-http-core-NEW
QUALIFICATION_TAG=inventory-concurrency docker compose --env-file /tmp/mf-ic.env \
  -p mf-inventory-concurrency -f deploy/compose/compose.yml \
  -f deploy/compose/qualification.yml down -v
```

## Limits

One laptop, one PostgreSQL without replicas, one shared Docker VM with unrelated
workloads; harness-1 and harness-2 differ by up to 3× in the most contended cells.
Three repetitions per cell give medians, not confidence intervals. The harness
reserves single-line, all-or-nothing requests; partial and multi-line allocation
exist only in the production path. The HTTP test uses one SKU, one warehouse and
one principal, and the gateway variant is paced by its rate limiter, so neither
is a saturation test of the gateway.
