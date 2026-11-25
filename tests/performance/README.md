# Performance and contention tests

All data is synthetic. Results go to a new directory; every script refuses to
overwrite an existing one. Keep raw output under `docs/evidence/<dated>/raw/`, which
Git ignores, and commit only the small summaries.

| Tool | What it measures | Needs |
|---|---|---|
| `MaterialFlowBenchmark` (JMH) | Pricing, approval and event-serialization CPU cost | Java 21 |
| `orders-load.jmx` + `run-formal-baseline.sh` | Capped-rate order writes through the JWT gateway | Qualification stack, JMeter 5.6.3 |
| `ReservationContentionHarness` + `run-reservation-contention.sh` | Three inventory reservation strategies on one hot SKU and on 100 SKUs | Java 21, Docker; or a dedicated external PostgreSQL database (`--jdbc-url`) |
| `hot-sku-orders.jmx` + `run-hot-sku-orders.sh` | Concurrent orders for one SKU through the real HTTP API, then SQL invariants | Qualification stack and JMeter 5.6.3, or the AKS environment ([Running on AKS](#running-on-aks)) |

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

## Running on AKS

The AKS environment ([runbook](../../docs/runbooks/azure-deploy.md)) has a tainted
load-generator node pool for test clients. Build the client image once (Arm64, from the
performance module after `mvn -B -ntp -pl tests/performance -am package`): it holds Apache
JMeter 5.6.3 from the Apache archive and k6 1.8.2 from its GitHub release, both checked
against checksums pinned in [`kubernetes/loadgen.Dockerfile`](kubernetes/loadgen.Dockerfile),
and the harness JAR. Test plans and scripts are streamed into the pod at run time.

```bash
export KUBECONFIG=~/materialflow-azure/kubeconfig MATERIALFLOW_KUBE_CONTEXT=aks-materialflow-dev
export MATERIALFLOW_PUBLIC_URL=https://materialflow-erp.westus.cloudapp.azure.com
ACR=$(terraform -chdir=deploy/terraform/azure output -raw acr_login_server)
docker buildx build --platform linux/arm64 -f tests/performance/kubernetes/loadgen.Dockerfile \
  -t "$ACR/materialflow/loadgen:<tag>" --push tests/performance
sed "s|LOADGEN_IMAGE|$ACR/materialflow/loadgen:<tag>|" tests/performance/kubernetes/loadgen-pod.yaml | kubectl apply -f -
```

**Hot-SKU orders.** `MATERIALFLOW_HOT_SKU_BACKEND=kubernetes` runs JMeter in the loadgen pod
against the in-cluster Services (`gateway`: `materialflow-edge-gateway:8081`, `core`:
`materialflow-erp-core:8080`), takes the token from the public Keycloak URL and runs the
seeding and `hot-sku-invariants.sql` through the psql toolbox pod against the managed
database. The host sampler records `kubectl top` for the nodes and the request-path pods
instead of `docker stats`.

```bash
MATERIALFLOW_HOT_SKU_BACKEND=kubernetes bash tests/performance/run-hot-sku-orders.sh /tmp/aks-http-gateway-NEW
MATERIALFLOW_HOT_SKU_BACKEND=kubernetes MATERIALFLOW_HOT_SKU_TARGET=core MATERIALFLOW_HOT_SKU_PAUSE_MS=0 \
  bash tests/performance/run-hot-sku-orders.sh /tmp/aks-http-core-NEW
```

The nodes are burstable (B2ps_v2: 40% baseline; B2pls_v2: 30%). Read the CPU credits
before and after a run:

```bash
az monitor metrics list --resource <node VMSS resource ID> --metric "CPU Credits Remaining" "Percentage CPU" \
  --dimension VMName --interval PT1M --offset 1h -o json
```

**Reservation harness against the managed server.** The harness has an external-JDBC
mode (`--jdbc-url`, `--jdbc-user`, password in `MATERIALFLOW_HARNESS_JDBC_PASSWORD`). Give
it a dedicated database and role, never the application database (it drops and
recreates its tables), and run it from the loadgen pod:

```bash
umask 077; openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32 | sed 's/$/\n/' > ~/harness-password
{ printf "CREATE ROLE harness LOGIN PASSWORD '"; tr -d '\n' < ~/harness-password; printf "';\n"; } \
  | kubectl -n materialflow exec -i deploy/materialflow-toolbox -- psql -v ON_ERROR_STOP=1 -d postgres
kubectl -n materialflow exec deploy/materialflow-toolbox -- psql -d postgres -c 'CREATE DATABASE harness OWNER harness'
# On Flexible Server the public schema belongs to azure_pg_admin, so give the role its own schema:
kubectl -n materialflow exec deploy/materialflow-toolbox -- psql -d harness \
  -c 'CREATE SCHEMA harness AUTHORIZATION harness' -c 'ALTER ROLE harness IN DATABASE harness SET search_path = harness'
HARNESS_JDBC_URL="jdbc:postgresql://<server>.postgres.database.azure.com:5432/harness?sslmode=require" \
HARNESS_JDBC_USER=harness HARNESS_PASSWORD_FILE=~/harness-password \
  bash tests/performance/kubernetes/run-reservation-contention.sh /tmp/aks-harness-NEW \
  --requests=4000 --repetitions=3 --workers=8,32,64,128
# afterwards
kubectl -n materialflow exec deploy/materialflow-toolbox -- psql -d postgres -c 'DROP DATABASE harness WITH (FORCE)' -c 'DROP ROLE harness'
```

The password is streamed to the pod on stdin and never appears on a command line. Both
modes first measure the round trip (`SELECT 1` on a pooled connection, 200 samples,
`roundTripBefore`/`roundTripAfter` in `summary.json`).

## Python helpers

```bash
python3 -m unittest discover -s tests/performance -p 'test_*.py'
```
