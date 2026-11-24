# Controlled failure drills

Run these scripts only against the local synthetic Compose stack. They stop and
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

If a run is interrupted outside its cleanup, inspect `pg_stat_activity` for
`mf_qualification_*` lock holders and `pg_trigger` for matching fixtures; holders
end on their own after 180 seconds. Never point the drill at a shared database.

The parsing, aggregation and argument handling are unit-tested without Docker:

```bash
python3 -m unittest discover -s tests/failure -p 'test_*.py'
```
