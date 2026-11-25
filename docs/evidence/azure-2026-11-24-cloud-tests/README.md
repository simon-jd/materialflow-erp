# Cloud tests on AKS, 2026-11-24/25: overview

Four tests that had only run on a laptop were repeated on the Azure environment from the
[platform bring-up](../azure-2026-11-24-platform/README.md): AKS 1.37 on Arm64 burstable nodes,
PostgreSQL on a managed Flexible Server, everything reached from a test-client pod on a separate,
tainted node pool. One test at a time, synthetic data only. Labels as in the repository:
**measured**, **derived**, **estimated**.

| Test | Result | Evidence |
|---|---|---|
| Process-kill fault matrix (4 crash windows × 5 SIGKILLs) | 20 of 20 kills exited 137 and restarted in place; 20 of 20 runs restored their invariant; consumer recovery 9.8–16.4 s, always on the surviving replica, while the kubelet's back-off delayed the killed container's restart by up to 325 s | [fault-matrix](../azure-2026-11-24-fault-matrix/README.md) |
| Zero-downtime operations at 15 orders/s | Before fixes: 6 failed requests in an erp-core rollout and a 15-minute outage in a node drain. After two chart fixes and an N+1 drain procedure: 0 failed requests in 6 of 6 rollout/delete runs and 2 of 3 drains; the third drain cost 311 × 503 in 21 s because Redis is a single replica | [zero-downtime](../azure-2026-11-24-zero-downtime/README.md) |
| Hot-SKU orders through the HTTP API (1 warm-up + 3 runs per path) | Every SQL invariant held in all 6 measured runs; 2 of 3,000 gateway requests shed with 503 (fail-closed rate limiter); erp-core direct p50/p95/p99 679/1,748/2,015 ms at 58.7 requests/s | [hot-sku](../azure-2026-11-24-hot-sku/README.md) |
| Locking strategies against the managed PostgreSQL | Guarded strategies kept the invariant in 96 of 96 measured runs; same ranking as locally with wider gaps (conditional 1.6–3.4× pessimistic on one hot SKU; optimistic 23–40 attempts/s and 64–65% exhausted from 32 workers on); every cell at 0.02–0.47× the laptop rate, bound by the managed disk (100% of provisioned IOPS in 43 of 46 minutes); round trip p50 0.48–0.68 ms | [locking-harness](../azure-2026-11-24-locking-harness/README.md) |

## Cloud vs local

| Measure | Local (Apple M3 Pro, Docker Desktop) | AKS (westus) | Label |
|---|---:|---:|---|
| Consumer recovery after SIGKILL, p50 of windows 1/2/4 (10 s session) | 10.7 / 7.9 / 11.1 s | 15.8 / 14.0 / 13.8 s | measured |
| Relay recovery, kill → republished (claim → republished) | 27.2 s (lease from claim) | 17.8 s (30.2–31.8 s) | measured |
| Kill → container running | 0.23–0.39 s (`docker start`) | 0.5–325 s (kubelet back-off) | measured |
| Hot SKU, erp-core direct p50 / p95 / p99 | 277 / 508 / 559 ms | 679 / 1,748 / 2,015 ms | measured (median of 3 runs) |
| Hot SKU, erp-core direct throughput | 190.0 requests/s | 58.7 requests/s | measured |
| Hot SKU, serialized time per order on the row | 4.4–7.3 ms | 17.1–19.9 ms | derived (run duration / 450) |
| Hot SKU, gateway p50 / p95 / p99 | 373 / 975 / 1,102 ms | 925 / 1,362 / 1,538 ms | measured (median of 3 runs) |
| Harness, hot SKU 8 workers, attempts/s: pessimistic / optimistic / conditional | 1,361 / 2,232 / 2,600 | 153 / 124 / 518 | measured (median of 3) |
| Harness, hot SKU 128 workers, attempts/s: pessimistic / optimistic / conditional | 2,126 / 914 / 3,109 | 189 / 26 / 377 | measured (median of 3) |
| Harness, serialized time per pessimistic reservation on the hot row | 0.47–0.73 ms | 5.3–6.5 ms | derived (1 / attempts/s) |
| Client → database round trip (`SELECT 1`, p50) | not measured (loopback) | 0.68 ms before, 0.48 ms after the harness run | measured |

What changes in the cloud, in one paragraph: correctness did not (every invariant that held locally
held on AKS, in every run), but every database round trip leaves the node and every commit waits
for a network-attached disk, so anything serialized on one hot row is 3–4× slower; failure
detection and recovery look like the laptop's once the second replica is taken into account; and
operational events that cannot happen in Compose (node drains, kubelet back-off, scheduler
preemption, pooled connections to terminating pods) produced the only failed requests. Those were
fixed in the chart or the procedure, except the single Redis replica, which is a recorded design
limit of this environment.

## Decisions recorded

- [ADR 0011](../../adr/0011-consumer-failure-detection.md): the 10 s session timeout bounds consumer
  recovery with two replicas; static membership would not help in a Deployment.
- [ADR 0012](../../adr/0012-azure-aks-deployment.md): node maintenance needs N+1 capacity (loadgen
  quota), Redis is the API's single point of failure, two chart fixes.
- [ADR 0008](../../adr/0008-inventory-reservation-concurrency.md): the ranking of the strategies holds against managed PostgreSQL with wider gaps; commit latency on the managed disk dominates the lock span, so the disk tier is the first lever.

## Observations outside the four tests

- **Reservation expiry blocked by one bad order.** After the fault matrix, erp-core's
  `ReservationExpiryScheduler` failed every 30 s with `Cannot release more credit than is reserved`
  for the 14 fault-matrix fixture orders (seeded directly in SQL without a credit reservation). The
  scheduler processes the 25 oldest expired orders and lets the exception end the batch, so while
  those orders were first in line, 13,306 expired reservations of other orders were not released
  (measured, `inventory_reservations` at 23:41 UTC). The fixtures are unrealistic, but one order that
  cannot be released should not stop all expiries. Not fixed here (application code, outside this
  work); the data reset removed the fixtures.
- **Relay dead-lettering during an outage.** During the 78 minutes in which Kafka was unscheduled
  after the first drain, the relay moved 11 outbox events to `DEAD_LETTER` after its retries.
- **Cost data lag.** Cost Management returned no rows for the two resource groups during the whole
  session (queried at 19:39, 20:12, 00:19 and 01:25 UTC).

## Spend

| | Amount | Label |
|---|---:|---|
| Cost Management query (ActualCost, month to date, both resource groups) at 00:19 and 01:22 UTC | no rows yet (usage data lags by hours) | measured |
| Tests, 19:41–01:22 UTC (5.7 h of the environment running) | about $2.7–3.0 | estimated: runbook rate $0.48–0.53/h × hours |
| Whole environment so far, platform included (18:43–01:22 UTC) | about $3.2–3.5 | estimated, same rate |
| Extra app node for the N+1 drains (~25 min of a B2ps_v2 at $0.08/h, while the $0.04/h loadgen node was at 0) | about +$0.02 | estimated |
| Rate while left running for the CD workflow | about $0.48–0.53/h; about $0.05/h stopped | estimated (runbook) |

Well inside the $40 limit for platform and tests together.

## Environment

[environment.json](environment.json): region, VM and database SKUs, Kubernetes, image digests,
client image and tool versions. Per-test details are in each folder.
