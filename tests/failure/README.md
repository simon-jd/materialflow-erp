# Controlled failure drills

Run these scripts only against the local synthetic Compose stack, except the two
marked for Kubernetes below (the process-kill matrix's `kubernetes` backend and
`kubernetes-zero-downtime.sh`), which run against the disposable AKS environment. They stop and
restart named project services, write synthetic orders, and leave raw output in
`/tmp`. Each script fails unless its recovery invariant is observed.

```bash
tests/failure/kafka-outage-recovery.sh
tests/failure/redis-fail-closed.sh
tests/failure/postgres-lock-timeout.sh
tests/failure/otel-collector-outage.sh
tests/failure/kubernetes-rollout.sh
```

The Kubernetes drill selects only the explicit disposable `kind-materialflow`
context, deletes one ERP Core Pod, and submits synthetic transactions during a
forced rolling update. It never selects or mutates an arbitrary current cluster.

## Process-kill fault matrix

`process-kill-recovery.py` (code in [`fault_matrix/`](fault_matrix)) parks the real
erp-core or event-relay JVM inside one crash window of the outbox → Kafka →
consumer-inbox path, sends it `SIGKILL`, restarts the container and waits for the
window's invariant. It accepts only the disposable qualification Compose projects
and requires app containers with restart policy `no`, so every restart is the
drill's own `docker start`.

| # | Window | Killed | Parked by | Invariant after restart |
|---|---|---|---|---|
| 1 | `consumer-pre-commit` | erp-core | fixture trigger on the inbox `INSERT`, inside the consumer transaction | exactly one inbox row and one business update; source offset advances; a later duplicate delivery is absorbed |
| 2 | `consumer-post-commit` | erp-core | fault-injection gate after the inbox + business transaction committed | the redelivered record is absorbed by the inbox key: still one business update; offset advances |
| 3 | `relay-post-ack` | event-relay | fixture trigger on the `PUBLISHED` update, after Kafka acknowledged the send | the claim lease expires and the event is republished: two broker records with the same `event_id`, `PUBLISHED` on attempt 2 |
| 4 | `dlq-post-ack` | erp-core | fault-injection gate after the DLQ send was acknowledged | the redelivered invalid record is quarantined again (DLQ duplicate) and the source offset advances |

Windows 1 and 2 publish an `InventoryReceived` event for a freshly seeded
BACKORDERED sales order whose converted purchase order has stock on hand, so the
consumer performs a real backorder reallocation. "One business update" means one
`RESERVE` ledger row, reserved and allocated quantity equal to the order line,
order status `RESERVED` and one `BackorderReallocated` follow-up event. Window 4
publishes an envelope without `organizationId`, a permanent
`InvalidEventEnvelopeException` that goes straight to the DLQ.

### How a run parks the JVM

The drill holds a fresh PostgreSQL advisory lock from its own `psql` session and
waits until an application session queues behind it in `pg_locks`; the queued
statement is recorded as `blocked_statement`. It then kills the JVM, releases the
lock, drops any fixture trigger and restarts the container.

- Windows 1 and 3 have a database write inside the window, so an event-specific
  trigger (`mf_qualification_*`) takes the lock in the application's own
  transaction. Triggers are created per run and dropped afterwards; they are never
  part of a Flyway migration.
- Windows 2 and 4 have no database write between the side effect and the offset
  commit. erp-core's `ConsumerCrashWindowGate` is called at exactly those two
  points: after `consume()` returned (transaction committed) and after the
  dead-letter publisher returned (send acknowledged), both before the listener
  container commits the offset. Normal wiring is a no-op. Only the
  `fault-injection` Spring profile installs `AdvisoryLockCrashWindowGate`, which
  waits on the advisory lock named by the record header
  `materialflow-crash-gate-after-inbox-commit` or
  `materialflow-crash-gate-after-dead-letter-send`. The drill has released the
  lock before the restart, so the redelivered record passes straight through.
  `ConsumerCrashWindowGateTest` proves the default wiring is inert, and
  `DomainEventConsumerRecoveryIntegrationTest` proves each gate runs after its side
  effect is durable and before its offset is committed.

### Run it

```bash
bash tests/deployment/build-runtime-images.sh fault-matrix
cp deploy/compose/qualification.env /tmp/mf-fault-matrix.env   # change ports if they collide
echo QUALIFICATION_TAG=fault-matrix >> /tmp/mf-fault-matrix.env
docker compose --env-file /tmp/mf-fault-matrix.env -p mf-fault-matrix \
  -f deploy/compose/compose.yml -f deploy/compose/qualification.yml \
  -f deploy/compose/fault-injection.yml \
  up -d --no-build postgres redis kafka kafka-init erp-core event-relay
python3 tests/failure/process-kill-recovery.py --project mf-fault-matrix \
  --windows all --repeat 5 --output-dir /tmp/mf-fault-matrix-NEW
docker compose --env-file /tmp/mf-fault-matrix.env -p mf-fault-matrix \
  -f deploy/compose/compose.yml -f deploy/compose/qualification.yml \
  -f deploy/compose/fault-injection.yml down -v
```

`--windows` takes `all` or numbers/names (`1,3`, `dlq-post-ack`); windows 2 and 4
refuse to start unless erp-core runs with `SPRING_PROFILES_ACTIVE=fault-injection`.
Runs go round-robin across the selected windows. The output directory must not
exist. `kills.jsonl` gets one record per run as soon as it finishes: window,
iteration, `event_id`, killed container with PID before/after and exit code 137,
source record offset, state at the kill (inbox/business/DLQ counts, committed
offset), state after recovery, the blocked statement, `restart_ms`, `recovery_ms`
(SIGKILL → invariant observed) and `passed`/`failed` with the failed assertion.
`summary.json` holds per-window passes and nearest-rank recovery p50/max. A failed
run is recorded and the matrix continues; it aborts only if an app container stays
down. The process exits non-zero unless every run passed.

`recovery_ms` runs from the moment `docker kill` returned to the first probe that
observed the full invariant. It includes the drill's own release-and-restart step
(`restart_ms`, about 0.3 s), JVM startup and, for the consumer windows, the
consumer-group rebalance. It is an observed upper bound: database probes repeat
every 250 ms, but each committed-offset probe is a `kafka-consumer-groups.sh` call
of roughly 2.5–3 s. Window 2's duplicate check reads `event_duplicate_total` from
erp-core's Prometheus endpoint and is recorded as `null` on a JWT-protected stack.
A consumer window recovers once the restarted JVM gets its partition back, which
the classic consumer group allows only after the killed member's session expires
(10 s by default, see [ADR 0011](../../docs/adr/0011-consumer-failure-detection.md)).
The relay window waits for the 30 s claim lease. Measured recovery times, before
and after the session-timeout change, are in the
[evidence](../../docs/evidence/local-2026-11-23-fault-matrix/README.md).

If a run is interrupted outside its cleanup, inspect `pg_stat_activity` for
`mf_qualification_*` lock holders and `pg_trigger` for matching fixtures; holders
end on their own after 180 seconds. Never point the drill at a shared database.

### Run it on Kubernetes (AKS)

The same windows run against a namespace deployed with the application chart's
`faultInjection.enabled=true` (`--backend kubernetes`). That switch gives erp-core and
event-relay `shareProcessNamespace: true` (the JVM is then not PID 1, so `kill -KILL`
from `kubectl exec` is delivered) and erp-core's `fault-injection` profile, and labels
the pods `materialflow.io/fault-injection=enabled`. The backend refuses to start
unless every erp-core and event-relay pod carries that label, and it needs the kube
context by name; it never uses the current context implicitly.

```bash
export KUBECONFIG=~/materialflow-azure/kubeconfig U=https://materialflow-erp.westus.cloudapp.azure.com
helm upgrade materialflow deploy/helm/materialflow -n materialflow --reuse-values \
  --set faultInjection.enabled=true --wait
umask 077; curl -fsS "$U/idp/realms/materialflow/protocol/openid-connect/token" \
  -d client_id=materialflow-cli -d grant_type=password -d username=synthetic-demo \
  -d password=synthetic-demo-only | jq -r .access_token > /tmp/mf-token   # optional, window 2 metrics
python3 tests/failure/process-kill-recovery.py --backend kubernetes \
  --kube-context aks-materialflow-dev --token-file /tmp/mf-token \
  --windows all --repeat 5 --output-dir /tmp/mf-fault-matrix-aks-NEW
helm upgrade materialflow deploy/helm/materialflow -n materialflow --reuse-values \
  --set faultInjection.enabled=false --wait
```

What changes against Compose:

- **SQL and Kafka.** `psql` runs in the toolbox pod (`app=materialflow-toolbox`, admin
  connection to the managed server over TLS), the Kafka CLI in `kafka-0` with
  `KAFKA_HEAP_OPTS=-Xms32m -Xmx256m` so a tool JVM cannot push the broker container
  over its 1 GiB limit. Each call goes through the API server: about 0.8 s per SQL
  probe and 4 s per `kafka-consumer-groups.sh` (measured from the operator machine).
- **Which pod is killed.** With two replicas the backend finds the parked JVM: for
  erp-core the consumer-group member that owns the domain topic's only partition (the
  group's HOST column is the pod IP), for event-relay the pod named in the outbox row's
  `claimed_by` (the relay's instance ID is its pod name). It then runs
  `kill -KILL <pid of java>` in that container.
- **Restart.** The kubelet restarts the container in place; the drill does nothing.
  A background poller (`kubectl get pod`, about 0.4 s per reading) timestamps the
  container status, so every record has `container_running_ms` and
  `container_ready_ms` (kill → restarted container running / Ready) next to
  `recovery_ms` (kill → invariant observed), plus the waiting reasons seen in between
  (`CrashLoopBackOff` means the kubelet held the restart back). `restart_ms` is set
  to `container_running_ms`, so `restart_to_recovered_ms` is negative when the
  invariant was restored before the killed container was even running again.
  `recovered_by` says whether the partition owner at the moment of recovery was the
  restarted pod or the other replica. After each run the drill waits until the killed
  container is Ready and, for erp-core, until the consumer group is `Stable` with every
  replica, so each run starts from the same state.
- **Crash back-off.** The kubelet delays a restart until the current back-off has passed
  since the container *exited* (10 s, doubling to 300 s); the back-off resets only when
  10 minutes pass between two exits of the same container. Repeated kills of one pod
  therefore restart after 0, 10, 20, 40 ... seconds however long it ran in between.
  The records show it; recovery with two replicas does not depend on it (see the
  [AKS evidence](../../docs/evidence/azure-2026-11-24-fault-matrix/README.md)).
- **Window 2 duplicates** are read from `event_duplicate_total` on every erp-core pod
  (with the token from `--token-file`, passed on stdin) and summed as an increase per
  JVM, because the redelivery may land on the surviving replica.
- **Window 3** also records `claim_to_republished_ms` from the database clock: the
  republish waits for the 30 s lease that started at the first claim, so
  kill → recovered depends on how long the drill spent between claim and kill.

Fixture orders (`FM-SO-*`) and one `materialflow.qualification.<event id>` topic per
window-3 run stay behind; reset the synthetic data afterwards
([runbook](../../docs/runbooks/azure-deploy.md#reset-synthetic-data)).

## Zero-downtime operations under load (Kubernetes)

`kubernetes-zero-downtime.sh DISRUPTION NEW_DIR` drives steady order traffic from the
load-generator pod (k6, constant arrival rate, default 15 orders/s, unique
Idempotency-Keys, `synthetic-demo` token) through the gateway's in-cluster Service,
waits `ZDT_BASELINE_SECONDS` (90), performs one disruption, waits until it has
completed (`kubectl rollout status`, or uncordon plus every Deployment Ready), keeps
the load for `ZDT_TAIL_SECONDS` (90) and stops k6.

| `DISRUPTION` | What it does |
|---|---|
| `rollout-erp-core` | `kubectl rollout restart deploy/materialflow-erp-core` |
| `rollout-edge-gateway` | `kubectl rollout restart deploy/materialflow-edge-gateway` |
| `delete-erp-core-pod` | `kubectl delete pod` of one erp-core pod |
| `drain-erp-core-node` | `kubectl drain` of the node hosting (most) erp-core pods, wait for erp-core, `uncordon` |

```bash
export MATERIALFLOW_KUBE_CONTEXT=aks-materialflow-dev MATERIALFLOW_PUBLIC_URL=$U
ACR=$(terraform -chdir=deploy/terraform/azure output -raw acr_login_server)
sed "s|LOADGEN_IMAGE|$ACR/materialflow/loadgen:<tag>|" tests/performance/kubernetes/loadgen-pod.yaml | kubectl apply -f -
bash tests/failure/kubernetes-zero-downtime.sh rollout-erp-core /tmp/zdt-rollout-erp-core-1
```

Each run seeds its own SKU (10,000,000 units, so every order allocates) and writes
`summary.json` (from `zero_downtime.py`): requests, failures (non-201, split into HTTP
errors and no response), status and k6 error codes, and p50/p95/p99 per window
(`steady` = the 60 s before the disruption, `disruption` = until it completed,
`after` = the tail), the p95/p99 ratio disruption/steady, every failure with its
offset from the disruption start, and the time to steady state: the start of the
first 5 s bucket after which every bucket has no failure and a p95 within the steady
p99. `orders.json` checks PostgreSQL: orders for the run's SKU = completed
idempotency keys ≥ acknowledged 201s. `timeline.txt`, `samples.txt` (node CPU and
pod placement every 10 s) and `events.txt` show what Kubernetes did. The script exits
non-zero if an acknowledged order is missing.

k6's `constant-arrival-rate` executor is used rather than JMeter: it is an open model
(a stall shows up as latency or errors, not as fewer requests; iterations it cannot
start count as `dropped_iterations`), its VUs keep HTTP keep-alive connections like a
real client, and it is a single static binary in the load-generator image.

The parsing, aggregation and argument handling are unit-tested without Docker or a cluster:

```bash
python3 -m unittest discover -s tests/failure -p 'test_*.py'
```
