# Locking strategies against the managed PostgreSQL, 2026-11-25

`ReservationContentionHarness` ([ADR 0008](../../adr/0008-inventory-reservation-concurrency.md))
compared pessimistic locking, optimistic retries and a conditional `UPDATE` on a disposable
PostgreSQL on a laptop. This run points the same harness, with the same settings, at a dedicated
`harness` database on the AKS environment's Flexible Server and runs it from the load-generator
node, so every statement crosses the VNet and every commit waits for the managed disk. All data
is synthetic. Labels: **measured**, **derived** (formula given).

## Result

128 runs (32 warm-ups + 96 measured) in one invocation, 2026-11-25 00:15–01:04 UTC, exit code 0.

| | Measured |
|---|---|
| Round trip, loadgen pod → Flexible Server (`SELECT 1`, 200 samples) | before: p50 0.68 ms, p95 2.41 ms, p99 4.63 ms (min 0.59); after: p50 0.48 ms, p95 0.55 ms, p99 0.90 ms |
| Invariant, guarded strategies (pessimistic, optimistic, conditional) | held in 72 of 72 measured runs and every warm-up; 0 units oversold |
| Invariant, unguarded control | violated in 24 of 24 measured runs (316–6,038 units oversold per run) |
| Hot SKU, 8 workers, median attempts/s | pessimistic 153, optimistic 124 (9.3% exhausted), conditional 518 |
| Hot SKU, 32–128 workers, median attempts/s | pessimistic 165–189, optimistic 23–40 (64–65% exhausted), conditional 296–377 |
| Storage | `disk_iops_consumed_percentage` ≥ 99% in 43 of 46 one-minute samples; database CPU median 11%, max 27% |

Against the laptop (harness-2), every cell ran at 0.02–0.47× the local rate (derived).

## Findings

- **Same ranking, wider gaps.** On one hot SKU the conditional `UPDATE` beat pessimistic locking at
  every worker count, by 1.6–3.4× (derived from the medians; 1.3–1.9× locally), and optimistic
  retries collapsed: from 32 workers on only 23–40 attempts/s, with 64–65% of all requests ending
  after 10 lost version races instead of with a reservation or a stock answer (6–15% locally).
  Each retry now pays several network round trips and a commit, so the window in which another
  writer can win grows with exactly the cost the retry is trying to avoid. Spread over 100 SKUs the
  three guarded strategies stayed within 2.1× of each other (1.35× locally), with no exhausted
  optimistic requests.
- **The managed disk, not the network or the CPU, sets the ceiling.** The round trip is 0.5–0.7 ms
  at p50, so a pessimistic reservation (lock, update, ledger insert, commit) costs a few
  milliseconds of network time; measured, the hot row admitted only 153–189 pessimistic
  attempts per second, i.e. 5.3–6.5 ms of serialized time each (derived: 1 / attempts/s),
  against 0.47–0.73 ms locally. During the run the 32 GiB P4 volume reported 100% of its
  provisioned IOPS consumed in 43 of 46 minutes, while database CPU stayed at a median of 11% (at most 27%) and
  the client node at ≤ 22% (Azure Monitor, [pg-metrics.json](pg-metrics.json), [vm-metrics.json](vm-metrics.json)).
  Every commit waits for a WAL flush on that disk, and the row lock is held until the commit returns.
  The round-trip tail before the run (p99 4.6 ms) and after it (0.9 ms) is consistent with the
  same contention. A bigger disk tier (more IOPS) is the first lever, before any code change.
- **Pool queueing shows up as errors earlier.** From 32 workers on, pessimistic, conditional and the control recorded
  3 s connection-acquisition timeouts (up to 2,013 of 12,000 requests for pessimistic at 128 workers),
  where locally only the noisier invocation had any. Slower transactions hold the 20 pooled
  connections longer, so the same closed-loop load waits longer for one. erp-core uses the same
  pool size and timeout, which matches the 3 s pool timeouts seen in the zero-downtime runs.
- **Correctness did not depend on the platform.** No guarded strategy oversold or lost an update in
  any of 96 measured runs or 32 warm-ups; the control oversold in every run.

## Results per cell

Medians of 3 measured repetitions per cell (measured per run, median derived), next to the local
[harness-2](../local-2026-11-23-inventory-concurrency/README.md#1-strategy-comparison-harness)
invocation (the quieter of the two local runs). "Exhausted" counts optimistic requests that lost 10
version races; "errors" are mostly 3 s connection-pool acquisition timeouts
(`SQLTransientConnectionException`), which the harness counts as failed requests, not as
reservations. Full data: [summary.csv](summary.csv), [summary.json](summary.json),
[runs.csv](runs.csv) (measured runs only).

### hot-sku

| Workers | Strategy | AKS attempts/s (min–max) | Local attempts/s | AKS / local | AKS p50 / p95 / p99 ms | Exhausted / errors (AKS, of 12,000) | Oversold |
|---:|---|---:|---:|---:|---|---|---:|
| 8 | pessimistic | 153 (120–314) | 1,361 | 0.11× | 52.1 / 100.4 / 112.6 | 0 / 0 | 0 |
| 8 | optimistic | 124 (90–246) | 2,232 | 0.06× | 5.5 / 393.9 / 761.4 | 1,111 / 0 | 0 |
| 8 | conditional | 518 (297–591) | 2,600 | 0.20× | 5.7 / 61.0 / 110.9 | 0 / 0 | 0 |
| 8 | unguarded-control | 165 (148–294) | 1,262 | 0.13× | 36.8 / 147.5 / 245.3 | 0 / 0 | 18,004 |
| 32 | pessimistic | 165 (144–170) | 2,035 | 0.08× | 126.8 / 546.1 / 3,000.2 | 0 / 164 | 0 |
| 32 | optimistic | 23 (23–29) | 1,264 | 0.02× | 982.1 / 2,857.1 / 3,002.6 | 7,716 / 0 | 0 |
| 32 | conditional | 311 (240–334) | 3,163 | 0.10× | 6.4 / 374.0 / 1,923.2 | 0 / 78 | 0 |
| 32 | unguarded-control | 171 (159–291) | 1,638 | 0.10× | 76.2 / 545.7 / 3,000.2 | 0 / 110 | 17,677 |
| 64 | pessimistic | 189 (142–330) | 1,739 | 0.11× | 77.7 / 3,000.1 / 3,000.2 | 0 / 722 | 0 |
| 64 | optimistic | 40 (22–56) | 916 | 0.04× | 1,693.3 / 5,246.7 / 5,989.4 | 7,746 / 0 | 0 |
| 64 | conditional | 296 (274–575) | 3,108 | 0.10× | 12.0 / 1,504.6 / 3,000.2 | 0 / 362 | 0 |
| 64 | unguarded-control | 234 (185–306) | 1,732 | 0.13× | 63.4 / 2,478.9 / 3,000.2 | 0 / 531 | 16,373 |
| 128 | pessimistic | 189 (153–223) | 2,126 | 0.09× | 152.0 / 3,000.2 / 3,000.4 | 0 / 2013 | 0 |
| 128 | optimistic | 26 (23–31) | 914 | 0.03× | 4,746.7 / 11,492.4 / 13,036.2 | 7,679 / 48 | 0 |
| 128 | conditional | 377 (264–407) | 3,109 | 0.12× | 24.9 / 3,000.1 / 3,000.6 | 0 / 1072 | 0 |
| 128 | unguarded-control | 191 (176–194) | 1,828 | 0.10× | 109.9 / 3,000.2 / 3,000.7 | 0 / 1950 | 12,180 |

### spread-100-skus

| Workers | Strategy | AKS attempts/s (min–max) | Local attempts/s | AKS / local | AKS p50 / p95 / p99 ms | Exhausted / errors (AKS, of 12,000) | Oversold |
|---:|---|---:|---:|---:|---|---|---:|
| 8 | pessimistic | 1,370 (427–1,457) | 5,087 | 0.27× | 5.4 / 8.9 / 13.1 | 0 / 0 | 0 |
| 8 | optimistic | 696 (676–2,063) | 5,444 | 0.13× | 3.9 / 45.5 / 55.7 | 0 / 0 | 0 |
| 8 | conditional | 820 (772–2,566) | 6,510 | 0.13× | 3.5 / 41.5 / 54.9 | 0 / 0 | 0 |
| 8 | unguarded-control | 991 (709–1,529) | 5,955 | 0.17× | 4.2 / 40.6 / 55.9 | 0 / 0 | 1,022 |
| 32 | pessimistic | 930 (922–934) | 8,342 | 0.11× | 8.6 / 53.9 / 101.4 | 0 / 15 | 0 |
| 32 | optimistic | 1,311 (1,281–3,704) | 8,479 | 0.15× | 5.6 / 107.0 / 284.1 | 0 / 0 | 0 |
| 32 | conditional | 1,584 (1,563–4,344) | 10,680 | 0.15× | 4.6 / 47.5 / 62.5 | 0 / 0 | 0 |
| 32 | unguarded-control | 1,290 (1,284–1,637) | 8,872 | 0.15× | 6.5 / 52.4 / 98.0 | 0 / 2 | 2,578 |
| 64 | pessimistic | 2,554 (2,032–2,660) | 6,665 | 0.38× | 7.1 / 15.4 / 1,002.7 | 0 / 0 | 0 |
| 64 | optimistic | 3,169 (2,613–3,449) | 7,765 | 0.41× | 5.8 / 99.3 / 360.3 | 0 / 0 | 0 |
| 64 | conditional | 3,723 (2,613–4,051) | 8,978 | 0.41× | 4.9 / 14.8 / 763.6 | 0 / 0 | 0 |
| 64 | unguarded-control | 3,262 (3,145–3,458) | 6,897 | 0.47× | 6.4 / 14.9 / 752.0 | 0 / 0 | 2,555 |
| 128 | pessimistic | 1,024 (950–2,677) | 8,780 | 0.12× | 9.4 / 869.7 / 3,000.3 | 0 / 192 | 0 |
| 128 | optimistic | 2,137 (1,397–3,357) | 8,966 | 0.24× | 6.0 / 401.7 / 1,198.7 | 0 / 0 | 0 |
| 128 | conditional | 1,595 (1,571–1,599) | 8,733 | 0.18× | 5.5 / 55.4 / 2,501.1 | 0 / 0 | 0 |
| 128 | unguarded-control | 1,362 (1,298–3,505) | 9,170 | 0.15× | 7.1 / 56.0 / 2,903.4 | 0 / 88 | 2,577 |

## Method

- **Harness.** Unchanged except for the new external-JDBC mode: `--jdbc-url`, `--jdbc-user`, the
  password only from `MATERIALFLOW_HARNESS_JDBC_PASSWORD`. 4,000 requests per run, 1–5 units each,
  all-or-nothing, a ledger row in the same transaction, stock = half the demand; virtual-thread
  workers in a closed loop behind a 20-connection HikariCP pool with a 3 s acquisition timeout
  (erp-core's settings); per (workload, workers, strategy) cell one warm-up and three measured
  repetitions on identical seeded demand, strategy order rotated; workers 8, 32, 64, 128; hot SKU
  and 100 SKUs. Identical to the local command line.
- **Round trip.** Before and after the runs the harness times 200 `SELECT 1` on one pooled
  connection (after 20 warm-up queries): one network round trip plus a trivial query.
- **Where.** The harness JAR in the `loadgen` image runs in the `loadgen` pod on the
  load-generator node, started with
  [`tests/performance/kubernetes/run-reservation-contention.sh`](../../../tests/performance/kubernetes/run-reservation-contention.sh),
  which streams the password on stdin. The database `harness` and role `harness` exist only for
  this test (own schema, because on Flexible Server the `public` schema belongs to
  `azure_pg_admin`) and were dropped afterwards. The application database was not touched.

## Environment

| | |
|---|---|
| Database | Azure Database for PostgreSQL Flexible Server 17.11, General Purpose Standard_D2ds_v5 (2 vCores), 32 GiB Premium SSD P4, westus, private access, TLS (`pg_stat_ssl.ssl = t`), `fsync=on`, `synchronous_commit=on`, READ COMMITTED, `max_connections` 859, `shared_buffers` 2 GB (measured via the harness) |
| Client | `loadgen` pod on the load-generator node (Standard_B2pls_v2, Arm64, 2 vCPU, burstable 30% baseline; CPU credits 131 → 148 during the run), image `materialflow/loadgen:t20261124-1`, Eclipse Temurin 21.0.13, Linux 6.6 aarch64, 2 JVM processors |
| Driver and pool | pgjdbc and HikariCP from the build (same versions as the local run), pool 20, acquisition timeout 3 s |
| Local reference | Apple M3 Pro laptop, `postgres:17.11-alpine` in Docker with `fsync=on` ([local evidence](../local-2026-11-23-inventory-concurrency/README.md)) |

[`summary.json`](summary.json) holds the harness's own environment record and the round-trip
measurements; `raw/` (git-ignored) has the harness log, `runs.json` and node samples, and the
first attempt, which failed at table creation (`permission denied for schema public`) before
any run.

## Reproduce

See [tests/performance/README.md](../../../tests/performance/README.md#running-on-aks) for creating the
database and role, then:

```bash
HARNESS_JDBC_URL="jdbc:postgresql://<server>.postgres.database.azure.com:5432/harness?sslmode=require" \
HARNESS_JDBC_USER=harness HARNESS_PASSWORD_FILE=~/harness-password MATERIALFLOW_KUBE_CONTEXT=aks-materialflow-dev \
  bash tests/performance/kubernetes/run-reservation-contention.sh /tmp/aks-harness-NEW \
  --requests=4000 --repetitions=3 --workers=8,32,64,128
```

## Limits

One server, one client pod, one run of the whole command (the local evidence had two). The
database is General Purpose D2ds_v5 with the smallest disk (32 GiB P4); a larger disk tier has
more IOPS and would change the commit-bound numbers. The client node is a burstable Arm64 VM.
Three repetitions per cell give medians, not intervals.
