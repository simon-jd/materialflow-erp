# Hot-SKU orders through the HTTP API on AKS, 2026-11-24/25

The [local hot-SKU test](../local-2026-11-23-inventory-concurrency/README.md#2-hot-sku-through-the-http-api)
repeated in the cloud: the same JMeter plan, the same SQL invariants, run from the load-generator
node against the in-cluster Services, with PostgreSQL on the managed Flexible Server. All data
is synthetic. Labels: **measured**, **derived** (formula given), **estimated**.

## Result

| Path | Run | Requests (non-201) | p50 / p95 / p99 ms | Throughput, requests/s | Invariants |
|---|---:|---|---|---:|---|
| Gateway, 2 s between waves | warm-up | 500 (70 × 503) | 1,677 / 5,769 / 6,262 | 30.8 burst, 20.6 overall | not gated (392 orders) |
| | 1 | 500 (1 × 503) | 1,279 / 2,459 / 2,690 | 50.0 burst, 27.8 overall | all consistency checks hold; 449 of 450 orders (the 503 never reached erp-core) |
| | 2 | 500 (1 × 503) | 925 / 1,362 / 1,538 | 69.7 burst, 32.9 overall | same as run 1 |
| | 3 | 500 (0) | 781 / 1,173 / 1,330 | 80.8 burst, 35.2 overall | all hold |
| erp-core direct, no pauses | warm-up | 500 (0) | 797 / 1,842 / 2,109 | 55.0 | all hold |
| | 1 | 500 (0) | 679 / 1,744 / 2,015 | 55.9 | all hold |
| | 2 | 500 (0) | 651 / 1,846 / 2,040 | 56.2 | all hold |
| | 3 | 500 (0) | 684 / 1,748 / 1,980 | 64.9 | all hold |

Measured (per-run JTL summaries and invariant JSON in this folder). Medians of the per-run
percentiles (derived): gateway 925 / 1,362 / 1,538 ms, erp-core 679 / 1,748 / 2,015 ms; aggregate
erp-core throughput 58.7 requests/s ([gateway](http-gateway-summary.json),
[erp-core](http-core-summary.json)).

**SQL invariants on the managed database (measured), every run with all 500 requests answered
201** (both warm-ups excepted for the gateway; the core warm-up passed too): on hand 1,000,
reserved 1,000; reserved = Σ line allocations = Σ active reservations = Σ RESERVE ledger; one
reservation and one ledger row per allocated line; statuses exactly 142 RESERVED / 1
PARTIALLY_ALLOCATED / 307 BACKORDERED and consistent with allocations; every shortage has a
procurement suggestion; 450 orders = 450 completed idempotency records = 450 outbox events; credit
reserved = order totals ([gateway](http-gateway-invariants.json), [erp-core](http-core-invariants.json)).
The same outcome, to the order, as all six local runs.

**Gateway runs 1 and 2 each shed one request** with 503 `SHARED_RATE_LIMITER_UNAVAILABLE`: the
gateway's Redis probe exceeded its 250 ms limit during a 100-request wave and the gateway failed
closed, as ADR 0004 specifies. Those requests created nothing: 449 orders = 449 completed keys,
and every other check held; the only failed check is "one order per idempotency key", because
the expected count is 450. The run script therefore exits non-zero for the gateway variant, which
is recorded as is. The cold warm-up lost 70 of 500 the same way (locally 8 of 500).

## Findings

- **The hot row costs 3–4× more per order against the managed server.** Straight to erp-core, one
  run of 450 orders took 7.7–8.9 s, i.e. **17–20 ms of serialized time per order** on the hot
  inventory row (derived: run duration / 450; duplicates do not lock inventory), against 4.4–7.3 ms
  locally. Mid-burst PostgreSQL showed 18 sessions waiting on a lock (erp-core's two pools hold 20
  connections), as locally. The order transaction runs about ten statements while it holds the row
  (ADR 0008), each a network round trip to the Flexible Server (p50 0.68 ms measured by the harness
  from the load-generator node, [harness evidence](../azure-2026-11-24-locking-harness/README.md)), plus
  a WAL flush on its Premium SSD at commit. On the laptop all of that was a local socket and a
  local NVMe flush.
- **CPU was not the limit; credits did not run out.** Azure Monitor 1-minute averages on the two
  app nodes peaked at 45% and 43% CPU (maxima 81% / 76% within the minute of the gateway warm-up)
  and stayed at 13–39% otherwise; `CPU Credits Remaining` rose during the test (209.6 → 211.1 and
  221.3 → 223.3) and the load-generator VM's from 129.1 to 131.0 ([vm-metrics.json](vm-metrics.json)).
  `kubectl top` during the waves showed the app nodes at 13–30%. The B-series 40% baseline was
  therefore never binding: the test is bound by the hot row, as locally.
- **Through the gateway, latency is 1.4–2.4× local** (p50 781–1,279 ms vs 372–671 ms) and the
  burst rate 50–81 vs 92–150 requests/s; the first gateway run after the warm-up was the slowest and
  each run was faster than the one before (JIT and connection pools in two fresh gateway pods).

## Comparison with the local run

| | Local Docker, Apple M3 Pro ([evidence](../local-2026-11-23-inventory-concurrency/README.md)) | AKS: Arm64 B2ps_v2 nodes, managed PostgreSQL |
|---|---:|---:|
| erp-core direct p50 / p95 / p99 (median of 3 runs) | 277 / 508 / 559 ms | 679 / 1,748 / 2,015 ms |
| erp-core direct throughput (aggregate of 3 runs) | 190.0 requests/s | 58.7 requests/s |
| Serialized time per order on the hot row | 4.4–7.3 ms | 17.1–19.9 ms |
| Gateway p50 / p95 / p99 (median of 3 runs) | 373 / 975 / 1,102 ms | 925 / 1,362 / 1,538 ms |
| Gateway burst throughput | 91.7–149.7 requests/s | 50.0–80.8 requests/s |
| Non-201 in measured runs | 0 of 3,000 | 2 of 3,000 (gateway, 503 fail-closed) |
| SQL invariants in measured runs | 6 of 6 hold | 6 of 6 consistency-hold; 2 gateway runs short one order each |
| erp-core replicas / CPU per replica | 1 × 2 CPU | 2 × 2 CPU limit (100m request), behind a Service |

## Method

`MATERIALFLOW_HOT_SKU_BACKEND=kubernetes bash tests/performance/run-hot-sku-orders.sh` (see
[tests/performance/README.md](../../../tests/performance/README.md#running-on-aks)): JMeter 5.6.3 (Apache
archive, SHA-512 checked when the image was built) runs `hot-sku-orders.jmx` in the `loadgen` pod
on the tainted load-generator node; the plan is streamed into the pod per run. Targets:
`materialflow-edge-gateway.materialflow.svc.cluster.local:8081` (JWT, Redis rate limiter, 2 s
pause between waves) and `materialflow-erp-core.materialflow.svc.cluster.local:8080` (same JWT,
no pause). The token comes from the public Keycloak URL; seeding (a fresh SKU with 1,000 units per
run, 50 customers) and `hot-sku-invariants.sql` run through the psql toolbox against the managed
database. Per run: 5 waves of 100 simultaneous orders of 7 units, the last 10 threads of a wave
repeating the first 10 keys. One warm-up and three measured runs per path. The synthetic data was
reset (database dropped and re-migrated) just before, at 00:09 UTC.

## Environment

AKS 1.37.1 in westus, app pool 2 × Standard_B2ps_v2 (2 vCPU Arm64, 40% baseline), load-generator
pool 1 × Standard_B2pls_v2 (JMeter pod: 1–2 CPU, 1.5–2.5 GiB); 2 erp-core replicas (10 DB
connections each) and 2 gateway replicas from images `b20261124-1` (Helm revision 9); PostgreSQL
Flexible Server 17.11, GP Standard_D2ds_v5, 32 GiB P4 storage, TLS; client image
`materialflow/loadgen:t20261124-1` (OpenJDK 21.0.13, JMeter 5.6.3). Details:
[environment](../azure-2026-11-24-cloud-tests/environment.json), per-run `*-environment.txt` and
host samples in `raw/`.

## Reproduce

```bash
export KUBECONFIG=~/materialflow-azure/kubeconfig MATERIALFLOW_KUBE_CONTEXT=aks-materialflow-dev
export MATERIALFLOW_PUBLIC_URL=https://materialflow-erp.westus.cloudapp.azure.com MATERIALFLOW_HOT_SKU_BACKEND=kubernetes
bash tests/performance/run-hot-sku-orders.sh /tmp/aks-http-gateway-NEW
MATERIALFLOW_HOT_SKU_TARGET=core MATERIALFLOW_HOT_SKU_PAUSE_MS=0 bash tests/performance/run-hot-sku-orders.sh /tmp/aks-http-core-NEW
```

## Limits

One SKU, one principal, one afternoon; three measured runs per path give medians, not intervals.
The gateway variant is paced by its rate limiter, so it is not a gateway saturation test. Node and
database metrics have 1-minute resolution. The local and cloud numbers come from different
hardware, replica counts and storage; the comparison shows where the time goes, not a capacity
figure for either.
