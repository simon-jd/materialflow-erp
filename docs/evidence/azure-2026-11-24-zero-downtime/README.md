# Zero-downtime operations under load on AKS, 2026-11-24

Steady order traffic ran through the API gateway's in-cluster Service while four routine
operations happened: a rollout restart of erp-core, a rollout restart of the gateway, deleting one
erp-core pod, and draining the node that hosts erp-core. Failed requests were traced to their
cause, the chart was fixed, and every operation was repeated. All data is synthetic. Labels:
**measured** (straight from a run), **derived** (computed from measured values),
**estimated**.

## Result

| Operation | Before the fixes | After the fixes |
|---|---|---|
| `kubectl rollout restart` erp-core | 6 of 3,362 requests failed (HTTP 500) in run 1, 0 in run 2 | **0 failed** in 2 of 2 runs (6,882 requests) |
| `kubectl rollout restart` gateway | 0 failed (1 run) | **0 failed** in 2 of 2 runs (6,820 requests) |
| delete one erp-core pod | 0 failed (1 run) | **0 failed** in 2 of 2 runs (6,161 requests) |
| drain the erp-core node, uncordon | **outage**: 13,322 of 14,762 requests failed over 15 min; the drain never completed | with a temporary third node (N+1): 0 failed in 2 of 3 runs; **311 × 503 within 21 s** when the drained node hosted Redis |

Measured; per-run detail below. In every run, orders in PostgreSQL = completed idempotency keys
≥ acknowledged 201s: no acknowledged order was lost or duplicated, also during the outage.

## Findings

1. **Gateway reused keep-alive connections to a terminating erp-core (fixed).** During the first
   erp-core rollout one request failed with HTTP 500 at +32.0 s: the gateway logged
   `PrematureCloseException: Connection has been closed BEFORE response, while sending request body`
   on a pooled connection to the erp-core Service. The old pod had been deleted about 10 s earlier
   (its `preStop` sleep), so it had left the Service endpoints, but Reactor Netty's pool still held
   keep-alive connections opened to it. When the pod's graceful shutdown closed them, a request
   being written on one failed. Readiness gating and `preStop` were doing their job for *new*
   connections; pooled connections bypass both. Fix: the gateway retires pooled upstream
   connections after 5 s (`spring.cloud.gateway.server.webflux.httpclient.pool.max-life-time`),
   well inside the 10 s `preStop` window, so by SIGTERM no pooled connection to the old pod is left.
   It is a race: it hit once in the three before-fix runs that replaced erp-core pods, and the six
   rollout and delete runs after the fix logged no `PrematureCloseException` (gateway logs
   retained). Six clean runs do not prove the race gone; the fix removes its precondition (a pooled
   connection older than the `preStop` window), which is the argument for it.
2. **Hot-row convoys reach erp-core's 3 s connection timeout (not fixed; residual risk).** The other
   five 500s of that run came from erp-core itself at +53.5 s: `HikariPool-1 - Connection is not
   available, request timed out after 3001ms (total=10, active=10, idle=0, waiting=18)`. All orders
   of a run lock one inventory row, so anything that slows the lock holder queues every other
   order behind it. The per-second PostgreSQL samples added afterwards show two causes: a new JVM
   taking its first requests (an `idle in transaction` session for 2.0 s at the moment the first
   new pod got traffic), and storage, with the head of the lock queue in `WALWrite`/`WalSync` for
   1–2 s and up to 19 sessions waiting, **during a steady baseline with no operation running**
   (run `after-nplus1-drain-erp-core-node-2`, 23:55:14–18 UTC; the Flexible Server's 32 GiB P4
   disk has 120 baseline IOPS). Maximum latencies of 1.5–3.1 s appear in most runs, before and
   after the fixes; only that one run crossed the 3 s pool timeout. It is a property of one hot
   SKU on this database rather than of the rollout, so no chart change was made; the levers are
   in the application (shorter lock span after the inventory update, ADR 0008; a warm-up before
   readiness) or the storage tier.
3. **A node drain could not work on two nodes, and took everything down (procedure changed).** The
   first drain evicted the gateway, erp-core, Keycloak and the AKS system pods from one node. The
   remaining node could not hold them: non-DaemonSet pods request 2.62 vCPU and 9.4 GiB in total
   against 1.9 vCPU and about 6.6 GiB allocatable per node (derived from the pod specs; the two
   managed `istiod` replicas alone request 1 vCPU and 4 GiB). The evicted system pods run at
   `system-cluster-critical`/`system-node-critical` priority, so the scheduler **preempted** the
   application's default-priority pods on the *other* node to place them: Kafka, Redis, the toolbox,
   the stream projection and a freshly started gateway replica were removed from the node that was
   not being drained (pod placement sampled every 10 s). With Redis gone the gateway failed closed
   with 503 (ADR 0004) from +5.5 s; at +261 s the last gateway replica restarted after failing its
   probes and from then on requests were refused or timed out. Meanwhile the drain could not finish:
   both gateway replicas had been on the drained node after the gateway rollout, the PDB allowed
   one eviction, and the replacement could not be scheduled, so the second eviction was refused
   for 15 minutes. The drill script then stopped without uncordoning, so the environment stayed
   degraded (Kafka, Redis and Keycloak Pending) until the node was uncordoned by hand at 23:01 UTC,
   78 minutes after the drain started (11 outbox events had been
   dead-lettered by the relay after its retries in that time). Fixes: (a) topology spread now
   counts only pods of the same ReplicaSet (`matchLabelKeys: [pod-template-hash]`), because with
   the old pods counted both new gateway replicas had landed on one node; (b) the drill uncordons
   whatever stops it; (c) **node maintenance needs N+1 capacity**: in this subscription's 6-vCPU
   quota that means scaling the load-generator pool to 0 and the app pool to 3 for the maintenance
   window. With that, two of three drains ran with 0 failures.
4. **Redis is the remaining single point for the API (by design).** The N+1 drain that evicted
   Redis produced 311 HTTP 503s over 21 s (+3.2 s to +23.9 s, each answered in about 260 ms, i.e.
   the gateway's 250 ms Redis probe and fail-closed response from ADR 0004) until Redis was
   running on the new node. Drains of the node with Kafka and Keycloak caused no failures: orders
   only need PostgreSQL, the outbox absorbs Kafka's absence and both JVMs had cached the JWKS.
   Removing this window needs a replicated Redis (or Azure Managed Redis), which is outside a
   chart fix; it is recorded in ADR 0012.
5. **After a drain the cluster is fragmented.** Upgrading the chart right after the failed drain
   deadlocked: erp-core's surge pod could fit on neither node (one CPU-full with the relocated
   system pods, the other memory-full), and with `maxUnavailable: 0` the rollout could not
   proceed. Deleting the old erp-core pods one at a time let it finish; two relocated system pods
   were then deleted so that the next surge pod would fit.
   On a full two-node cluster, `maxSurge: 1` needs one pod's worth of free requests on some node.

## Method

[`tests/failure/kubernetes-zero-downtime.sh`](../../../tests/failure/kubernetes-zero-downtime.sh)
(described in [tests/failure/README.md](../../../tests/failure/README.md#zero-downtime-operations-under-load-kubernetes)),
one run at a time, from the operator machine with `nohup`:

- **Load.** k6 1.8.2 in the `loadgen` pod on the tainted load-generator node,
  `constant-arrival-rate` executor at **15 orders/s** (open model: up to 400 VUs, so a stall turns
  into latency, errors or `dropped_iterations` instead of a lower offered rate). Each iteration is
  one `POST /api/v1/orders` to `http://materialflow-edge-gateway.materialflow.svc.cluster.local:8081`
  with a unique `Idempotency-Key`, a `synthetic-demo` JWT (2 h lifetime) and a 10 s client
  timeout. Every run seeds its own SKU with 10,000,000 units, so every order allocates stock; all
  orders of a run therefore contend for one inventory row, a deliberately strict case.
- **Why k6.** JMeter's standard thread groups are closed loops (a slow response delays the next
  request, which hides exactly the stalls this test looks for), and arrival-rate scheduling needs
  its experimental Open Model Thread Group or a plugin. k6's arrival-rate executor is an open
  model, its VUs reuse keep-alive connections like a real client, it counts iterations it could
  not start, and it is one static binary (checksum-verified in the
  [load-generator image](../../../tests/performance/kubernetes/loadgen.Dockerfile)). JMeter stays
  the tool for the hot-SKU waves, which are closed-loop by design.
- **Timeline.** 90 s steady state, the operation (`kubectl rollout restart` + `rollout status`;
  `kubectl delete pod` + `rollout status`; `kubectl drain` + wait + `uncordon` + every Deployment
  Ready), then 90 s more. Windows are assigned by request start time: `steady` = 60 s before the
  operation, `disruption` = until the drill saw it complete, `after` = the tail.
- **Metrics** ([`zero_downtime.py`](../../../tests/failure/zero_downtime.py)): failures = anything
  but 201 (HTTP errors and no response), p50/p95/p99/max wall-clock latency per window, and time
  to steady state = start of the first 15 s bucket after which every bucket has no failure and a
  p95 within twice the steady window's p95, from the operation's start (and from its end). PostgreSQL check per run: orders for the run's SKU =
  completed idempotency keys ≥ acknowledged 201s (an order may exist for a request whose response
  the client never saw, which a retry with the same key would return).
- **Instrumentation** (added after the first run): PostgreSQL sessions once per second (lock
  waiters, idle-in-transaction sessions, longest open transaction, blockers), node CPU and pod
  placement every 10 s, the logs of every erp-core and gateway pod, and Kubernetes events.

Chart settings under test (from the platform release, unchanged unless listed under fixes):
2 replicas each of erp-core and the gateway, `RollingUpdate` with `maxSurge: 1,
maxUnavailable: 0`, PDB `maxUnavailable: 1`, readiness on `/actuator/health/readiness`,
`preStop` sleep 10 s, `terminationGracePeriodSeconds: 45`, `server.shutdown: graceful`,
topology spread `ScheduleAnyway` across nodes.

## Before the fixes

| Run | Operation took | Requests | Failed (codes) | p95 / p99 steady | p95 / p99 / max during | p95 / p99 after | Time to steady: from start (after finish) | k6 dropped | Orders = keys ≥ 201s |
|---|---:|---:|---|---|---|---|---|---:|---|
| [`before-rollout-erp-core-1`](runs/before-rollout-erp-core-1.json) | 43 s | 3,362 | **6** (500: 6) | 43 / 62 | 60 / 104 / 482 | 230 / 3,322 | 60 s (17 s) | 5 | 3,357 = 3,357 ≥ 3,356 ✓ |
| [`before-rollout-erp-core-2`](runs/before-rollout-erp-core-2.json) | 48 s | 3,451 | 0 | 29 / 37 | 91 / 165 / 3,056 | 50 / 85 | 90 s (42 s) | 0 | 3,452 = 3,452 ≥ 3,451 ✓ |
| [`before-rollout-edge-gateway-1`](runs/before-rollout-edge-gateway-1.json) | 47 s | 3,423 | 0 | 31 / 41 | 58 / 1,768 / 2,862 | 37 / 119 | 60 s (13 s) | 0 | 3,423 = 3,423 ≥ 3,423 ✓ |
| [`before-delete-erp-core-pod-1`](runs/before-delete-erp-core-pod-1.json) | 22 s | 3,073 | 0 | 29 / 34 | 43 / 56 / 62 | 45 / 78 | 0 s (0 s) | 0 | 3,074 = 3,074 ≥ 3,073 ✓ |
| [`before-drain-erp-core-node-1`](runs/before-drain-erp-core-node-1.json) | 909 s | 14,762 | **13,322** (no response: 9,484; 503: 3,838) | 28 / 38 | 10,001 / 10,001 / 10,003 | — / — | never | 111 | 1,440 = 1,440 ≥ 1,440 ✓ |

Run 1 had no PostgreSQL sampling yet; it was repeated (run 2) with it. The drain row is computed
from the requests k6 had recorded when the drill stopped (`events.json` there marks the end as the
last request; there is no "after" window).

## Fixes

| Change | Where | Addresses |
|---|---|---|
| `SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_POOL_MAXLIFETIME=5s` for the gateway | [`values.yaml`](../../../deploy/helm/materialflow/values.yaml) | finding 1 |
| `matchLabelKeys: [pod-template-hash]` in the topology spread constraint | [`_helpers.tpl`](../../../deploy/helm/materialflow/templates/_helpers.tpl) | finding 3, gateway PDB deadlock |
| Drill uncordons in its exit trap, records a drain that cannot finish instead of aborting, never drains the load generator's node, samples PostgreSQL locks and keeps pod logs | [`kubernetes-zero-downtime.sh`](../../../tests/failure/kubernetes-zero-downtime.sh) | finding 3 |
| N+1 maintenance procedure (loadgen pool to 0, app pool to 3, drain, uncordon, remove the extra node, loadgen back to 1) | [runbook](../../runbooks/azure-deploy.md#node-maintenance-n1) | finding 3 |

Deployed as Helm revision 9 with the same values files and image tag as the platform release
(`helm upgrade ... -f values-aks.yaml -f environment-values.yaml --set global.imageTag=b20261124-1`;
`helm template` of revision 8 vs 9 differs only in these two settings and comments).

## After the fixes

| Run | Operation took | Requests | Failed (codes) | p95 / p99 steady | p95 / p99 / max during | p95 / p99 after | Time to steady: from start (after finish) | k6 dropped | Orders = keys ≥ 201s |
|---|---:|---:|---|---|---|---|---|---:|---|
| [`after-rollout-erp-core-1`](runs/after-rollout-erp-core-1.json) | 50 s | 3,483 | 0 | 45 / 83 | 106 / 186 / 2,979 | 48 / 75 | 45 s (0 s) | 7 | 3,483 = 3,483 ≥ 3,483 ✓ |
| [`after-rollout-erp-core-2`](runs/after-rollout-erp-core-2.json) | 44 s | 3,399 | 0 | 29 / 35 | 59 / 85 / 1,878 | 41 / 65 | 60 s (16 s) | 0 | 3,400 = 3,400 ≥ 3,399 ✓ |
| [`after-rollout-edge-gateway-1`](runs/after-rollout-edge-gateway-1.json) | 45 s | 3,413 | 0 | 30 / 41 | 45 / 75 / 1,536 | 45 / 289 | 60 s (15 s) | 0 | 3,413 = 3,413 ≥ 3,413 ✓ |
| [`after-rollout-edge-gateway-2`](runs/after-rollout-edge-gateway-2.json) | 44 s | 3,407 | 0 | 31 / 40 | 41 / 931 / 1,798 | 39 / 77 | 60 s (16 s) | 0 | 3,408 = 3,408 ≥ 3,407 ✓ |
| [`after-delete-erp-core-pod-1`](runs/after-delete-erp-core-pod-1.json) | 22 s | 3,077 | 0 | 31 / 37 | 40 / 45 / 66 | 47 / 187 | 30 s (8 s) | 0 | 3,077 = 3,077 ≥ 3,077 ✓ |
| [`after-delete-erp-core-pod-2`](runs/after-delete-erp-core-pod-2.json) | 23 s | 3,084 | 0 | 31 / 35 | 31 / 40 / 55 | 38 / 50 | 0 s (0 s) | 0 | 3,084 = 3,084 ≥ 3,084 ✓ |
| [`after-nplus1-drain-erp-core-node-1`](runs/after-nplus1-drain-erp-core-node-1.json) | 102 s | 4,274 | **311** (503: 311) | 28 / 34 | 257 / 271 / 1,855 | 42 / 57 | 105 s (3 s) | 0 | 3,963 = 3,963 ≥ 3,963 ✓ |
| [`after-nplus1-drain-erp-core-node-2`](runs/after-nplus1-drain-erp-core-node-2.json) | 80 s | 3,936 | 0 | 637 / 1,887 | 47 / 82 / 2,754 | 38 / 58 | 0 s (0 s) | 0 | 3,936 = 3,936 ≥ 3,936 ✓ |
| [`after-nplus1-drain-erp-core-node-3`](runs/after-nplus1-drain-erp-core-node-3.json) | 68 s | 3,758 | 0 | 37 / 62 | 55 / 94 / 2,850 | 41 / 52 | 75 s (7 s) | 0 | 3,759 = 3,759 ≥ 3,758 ✓ |

The drains ran with a temporary third app node (above); the load generator ran on that node with
smaller requests, because the load-generator pool had to give its 2 vCPUs of quota to it, and the
drill picked the erp-core node without it. One more attempt is kept as invalid
(`raw/invalid-nplus1-drain-refused-client-node`): it picked the client's own node, `kubectl drain`
refused to evict a pod without a controller, nothing was disrupted, and the drill was changed to
skip that node. Run `after-nplus1-drain-erp-core-node-2` had a WAL-stall in its steady window
(finding 2), which inflates that window's percentiles and makes its time to steady 0 by
definition.

Time to steady state is computed with the definition above for all runs (15 s buckets, p95 within
2× the steady p95, no failures); the drill's first definition (5 s buckets, steady p99) was too
strict and is superseded. Compact per-run results are in [`runs/`](runs).

## Environment

Region westus, AKS 1.37.1, app pool 2 × Standard_B2ps_v2 (Arm64, burstable) and load-generator
pool 1 × Standard_B2pls_v2; PostgreSQL Flexible Server 17 GP Standard_D2ds_v5; images
`b20261124-1`; Helm revision 8 before and 9 after the fixes. Full detail:
[cloud-tests environment](../azure-2026-11-24-cloud-tests/environment.json).

## Reproduce

```bash
export KUBECONFIG=~/materialflow-azure/kubeconfig MATERIALFLOW_KUBE_CONTEXT=aks-materialflow-dev
export MATERIALFLOW_PUBLIC_URL=https://materialflow-erp.westus.cloudapp.azure.com
for d in rollout-erp-core rollout-edge-gateway delete-erp-core-pod; do
  bash tests/failure/kubernetes-zero-downtime.sh "$d" "/tmp/zdt-$d-$(date +%s)"
done
# Node drain with N+1 capacity (runbook "Node maintenance (N+1)"), client on the extra node:
MATERIALFLOW_LOADGEN_POD=loadgen-n1 bash tests/failure/kubernetes-zero-downtime.sh drain-erp-core-node /tmp/zdt-drain-$(date +%s)
```

`raw/` (git-ignored) keeps every run directory: `requests.jsonl.gz` (one line per request),
`summary.json`, `orders.json`, `timeline.txt`, `pg-samples.txt`, `samples.txt`, `logs/`, events.

## Limits

- 15 orders/s for one SKU from one principal; two app nodes; two to three runs per operation.
  A run without failures shows the mechanism works at this rate, not that failures are
  impossible: the races found here hit 1 in 2 runs and less.
- The client calls the gateway Service inside the cluster. The public path through the Envoy
  gateway (and its own retries and connection handling) was not part of this test.
- Single replicas of Redis, Kafka and Keycloak are a property of this dev environment
  ([ADR 0012](../../adr/0012-azure-aks-deployment.md)); the drain results depend on where they run.
