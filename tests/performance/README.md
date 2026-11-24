# Performance and contention tests

All data is synthetic. Results go to a new directory; every script refuses to
overwrite an existing one. Keep raw output under `docs/evidence/<dated>/raw/`, which
Git ignores, and commit only the small summaries.

| Tool | What it measures | Needs |
|---|---|---|
| `MaterialFlowBenchmark` (JMH) | Pricing, approval and event-serialization CPU cost | Java 21 |
| `orders-load.jmx` + `run-formal-baseline.sh` | Capped-rate order writes through the JWT gateway | Qualification stack, JMeter 5.6.3 |
| `ReservationContentionHarness` + `run-reservation-contention.sh` | Three inventory reservation strategies on one hot SKU and on 100 SKUs | Java 21, Docker |
| `hot-sku-orders.jmx` + `run-hot-sku-orders.sh` | Concurrent orders for one SKU through the real HTTP API, then SQL invariants | Qualification stack, JMeter 5.6.3 |

The JMeter-based scripts and the harness are load measurements: on a shared Docker
host, take the heavy-Docker lock and record what else is running (both new scripts
sample `docker ps` and the load average while they measure).

## Reservation strategy harness

Starts a disposable `postgres:17.11-alpine` (the Compose image, with Compose's
default `fsync=on` rather than the Testcontainers default `fsync=off`) and compares:

- `pessimistic`: `SELECT ... FOR UPDATE`, then a version-checked UPDATE, as `HotPathMapper` does;
- `optimistic`: unlocked read, `UPDATE ... WHERE version = ?`, up to 10 attempts with
  full-jitter backoff (2 ms doubling to a 100 ms cap);
- `conditional`: one `UPDATE ... WHERE on_hand - reserved >= ? RETURNING reserved`;
- `unguarded-control`: a deliberately broken read-modify-write that must oversell,
  proving the invariant check can fail.

Each successful reservation appends a ledger row in the same transaction. Workers
are virtual threads in a closed loop behind a 20-connection HikariCP pool with a
3 s acquisition timeout, matching erp-core. Every (workload, workers, strategy)
cell gets one warm-up and three measured repetitions on an identical seeded demand;
strategy order rotates per repetition. After each run the harness checks that what
clients were told was reserved equals the stored `reserved` and the ledger, and
that `reserved <= on_hand`. The balance table intentionally has no
`reserved <= on_hand` constraint, so the strategy itself is under test.

```bash
export JAVA_HOME=/path/to/jdk-21
bash tests/performance/run-reservation-contention.sh \
  docs/evidence/<dated>/raw/harness [--requests=4000] [--repetitions=3] \
  [--workers=8,32,64,128] [--pool-size=20] [--seed=20261123]
```

It writes `summary.json`, `summary.csv`, `runs.json`, `harness.log`,
`environment.txt` and `host-samples.txt`, and exits nonzero if a strategy other
than the control ever violated the invariant.

## Hot-SKU orders through the HTTP API

Start the qualification stack as in
[the runbook](../../docs/runbooks/runtime-qualification.md), with your own project
name, image tag and (if needed) a copy of `qualification.env` with free ports.
Each run seeds a fresh SKU (1000 units) and 50 synthetic customers, obtains a token
for the synthetic `synthetic-demo` user via `materialflow-cli`, and sends 5 waves
of 100 simultaneous `POST /api/v1/orders` (7 units each, partial fulfillment
allowed). In every wave the last 10 threads repeat the Idempotency-Key and body of
the first 10, so 450 distinct orders compete for stock that covers about 143.
`hot-sku-invariants.sql` then checks that `reserved <= on_hand`, that all stock is
allocated, that `reserved` equals the line allocations, the active reservations
and the RESERVE ledger, that statuses match allocations, that shortages became
procurement suggestions, and that there is exactly one order, one completed
idempotency record and one outbox event per key.

```bash
set -a; source /path/to/your-qualification.env; set +a
export COMPOSE_PROJECT_NAME=<your-project> JMETER_HOME=/path/to/apache-jmeter-5.6.3
bash tests/performance/run-hot-sku-orders.sh docs/evidence/<dated>/raw/http-gateway
MATERIALFLOW_HOT_SKU_TARGET=core MATERIALFLOW_HOT_SKU_PAUSE_MS=0 \
  bash tests/performance/run-hot-sku-orders.sh docs/evidence/<dated>/raw/http-core
```

`gateway` (default) goes through the JWT gateway. Its Redis rate limiter allows one
principal 50 requests/s with a burst of 100, so waves are capped at 100 and paused
2 s; `burst_throughput_rps` excludes those pauses. `core` sends the same JWT straight
to erp-core's resource server, bypassing the limiter, without pauses. Settings are
`MATERIALFLOW_HOT_SKU_{WARMUP_RUNS,RUNS,THREADS,WAVES,DUPLICATES,CUSTOMERS,QUANTITY,STOCK,PAUSE_MS}`
(defaults 1, 3, 100, 5, 10, 50, 7, 1000, 2000). Warm-up runs are recorded but not
gated: a cold gateway can fail closed with 503 while its Redis client warms up. The
script exits nonzero on any non-201 response or failed invariant in a measured run.

## Python helpers

```bash
python3 -m unittest discover -s tests/performance -p 'test_*.py'
```
