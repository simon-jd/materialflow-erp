# Local runtime qualification

Use only synthetic data. The dedicated Compose overlay binds every published port
to loopback, uses separate project volumes, enables JWT, disables automatic app
restart for observable crash tests and gives each application 2 CPU / 1 GiB.
Requires Java 21, Maven 3.9+, Docker with Compose >=2.24.4, Python 3, curl and jq.
It does not operate the ordinary `materialflow` deployment or other projects.

The one-shot `kafka-init` service creates the domain, consumer DLQ and projection
DLQ topics before Core, Relay and Streams start. Creation is idempotent. This avoids
relying on consumer auto-creation during a Streams rebalance, which can otherwise
end in `MissingSourceTopicException` on an empty broker. The one-partition,
replication-factor-one setup is explicitly local; an existing topic's partition
or replication settings are not changed by `--if-not-exists`.

## Build and start

```bash
mvn -B -ntp -Pquality clean verify
python3 -m unittest discover -s tests/performance -p 'test_*.py'
bash tests/deployment/build-runtime-images.sh boot4
docker compose --env-file deploy/compose/qualification.env \
  -p materialflow-qualification-final \
  -f deploy/compose/compose.yml -f deploy/compose/qualification.yml up -d --no-build
```

`Dockerfile.runtime` packages the JAR just tested. Capture its SHA-256 and the
resulting image ID; inspect `/opt/materialflow/app.jar` in each running app and
compare hashes. Do not rebuild JARs concurrently with image packaging. The regular
multi-stage Dockerfile remains available for source builds. The overlay's `build`
reset prevents an accidental second compilation.

If ports conflict, copy `qualification.env` to a temporary file and change them
consistently. The included smoke script uses the documented 28080–28084 / 28180 /
26686 defaults. Kafka's advertised external port now follows its published port.
The local Keycloak realm/users and passwords are synthetic fixtures only.

## Functional and telemetry checks

```bash
MATERIALFLOW_GATEWAY_URL=http://localhost:28081 \
MATERIALFLOW_KEYCLOAK_URL=http://localhost:28180 \
  bash tests/security/jwt-rbac-e2e.sh
# Supply the order_id printed by that script:
python3 tests/deployment/qualification-smoke.py --order-id ORDER_UUID \
  --output /tmp/materialflow-smoke-NEW.json
```

The security test checks unauthenticated rejection, authorized creation despite
spoofed identity headers, read-only-role rejection and cross-tenant order lookup.
The smoke test checks five health endpoints, a matching aggregate projection,
an actual HTTP → synthetic gRPC confirmation and the exported trace spanning
Gateway, Core, Relay and `domain event consume`. No real supplier is contacted.
The checks are specific assertions, not a full API/security audit.

## Repeatable capped-rate measurement

Install Apache JMeter 5.6.3 from its official distribution and check its SHA-512.
The default formal script still uses 600-second warmups and 1200-second measured
phases. The bounded migration protocol is explicitly overridden below:

```bash
set -a
source deploy/compose/qualification.env
set +a
export COMPOSE_PROJECT_NAME=materialflow-qualification-final
export JMETER_HOME=/path/to/apache-jmeter-5.6.3
export MATERIALFLOW_PERF_RUNS=3
export MATERIALFLOW_PERF_WARMUP_SECONDS=60 MATERIALFLOW_PERF_MEASURE_SECONDS=180
export MATERIALFLOW_PERF_THREADS=4 MATERIALFLOW_PERF_RATE_PER_MINUTE=600
bash tests/performance/run-formal-baseline.sh /tmp/materialflow-perf-NEW
```

The result directory must not exist. All samples require HTTP 201; connection and
response waits are bounded. CSV is parsed by header with quoting support. Run
duration uses the last completion across all threads, and aggregate percentiles
are the median of per-run nearest-rank percentiles. This is a capped workload,
not an open-loop saturation test. Keep raw JTLs, JMeter/environment/resource logs,
database counters, consumer offsets and image IDs. Never store access tokens.

For a before/after comparison, preserve pre-change JARs before editing and build
them under a separate tag using the second argument of `build-runtime-images.sh`.
Use a separate project with fresh volumes and the same supporting images/limits.
Stop that project before reusing the host ports. Do not compare the short protocol
directly with the historical September 1 long-duration numbers. Avoid concurrent
builds/scans during measured phases; the workstation still has unrelated workloads.

## Actual process death

Run after performance measurements when the stack is quiescent:

```bash
python3 tests/failure/process-kill-recovery.py \
  --project materialflow-qualification-final \
  --output /tmp/materialflow-process-kill-NEW.json
```

The script accepts only the three named qualification projects and verifies app
image labels and disabled restart policies. Unique event-specific PostgreSQL
triggers and advisory-lock holders expose the exact crash windows. It checks
SIGKILL exit code 137 and a new process start, then removes the fixture triggers
and restarts the killed application in `finally`.

- Consumer: block Inbox INSERT before commit; verify zero visible Inbox rows and
  unchanged committed source offset; kill the Core JVM, release the gate and
  restart. The retained record commits; republish the same ID and verify a second
  Kafka offset but still one Inbox row. The probe has no inventory side effect.
- Relay: after Kafka ACK, block the PUBLISHED database write; verify the Kafka
  record is already readable and the committed row is still CLAIMED. Kill the
  Relay JVM. After restart and natural lease expiry, verify PUBLISHED on attempt 2
  and the same event ID at offsets 0 and 1 in its isolated topic. This demonstrates
  at-least-once replay, not exactly-once delivery.

A failed script exits nonzero and retains a failed evidence record. If interrupted
outside normal cleanup, inspect `pg_stat_activity` for `mf_qualification_*` holders
and `pg_trigger` for matching fixtures in this disposable database. Lock holders
also have a 180-second upper bound. Do not run these fixtures on shared/production
databases. Inbox-commit→offset-commit and DLQ-ACK→offset-commit kills are separate,
unqualified windows.

## Preserve evidence and stop

Scan each app image using the current Trivy DB and record severity scope and image
ID. A zero High/Critical result does not cover lower severities, supporting service
images, configuration defects or future advisories. Re-run the full tests and
image checks after dependency fixes.

```bash
docker compose --env-file deploy/compose/qualification.env \
  -p materialflow-qualification-final \
  -f deploy/compose/compose.yml -f deploy/compose/qualification.yml stop
```

This preserves images and synthetic data volumes for inspection. Do not use a
global Docker prune. Cloud deployment, multi-node partitions, production-sized
migrations and hosted CI execution remain separate qualifications.
