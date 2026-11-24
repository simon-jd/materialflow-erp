# Process-kill fault matrix: November 23, 2026 session

The repository had described a 4-window × 5-kill fault matrix for the outbox →
Kafka → consumer-inbox path but had only run two SIGKILLs (windows 1 and 3, in
[the September 7 qualification](../local-2026-09-07-qualification/process-kill.json)).
This session made all four windows runnable and ran the full matrix. The matrix
showed that consumer recovery was bounded by Kafka's 45 s session timeout, so
[ADR 0011](../../adr/0011-consumer-failure-detection.md) made failure detection
configurable with a 10 s default, and the consumer windows were re-measured
([below](#session-timeout-re-measurement-adr-0011)). All data is synthetic and all
execution is local. Labels: **measured** values come straight
from the retained records, **derived** values are computed from them.

## Result

20 real SIGKILLs (4 windows × 5), 20 passed. Every kill exited 137 with no OOM
kill, and every restart produced a new container start time and process ID
(measured, [`matrix/kills.jsonl`](matrix/kills.jsonl)). Recovery = from `docker
kill` returning to the first probe that observed the window's full invariant
(measured per run; p50/max/min derived, nearest rank over 5 runs,
[`matrix/summary.json`](matrix/summary.json)).

| # | Window | Killed | SIGKILLs (exit 137) | Passed | Recovery p50 | Recovery max | Recovery min | Restart → recovered p50 |
|---|---|---|---:|---:|---:|---:|---:|---:|
| 1 | `consumer-pre-commit` | erp-core | 5 | 5/5 | 45.2 s | 47.0 s | 43.8 s | 44.8 s |
| 2 | `consumer-post-commit` | erp-core | 5 | 5/5 | 45.3 s | 46.0 s | 42.4 s | 44.9 s |
| 3 | `relay-post-ack` | event-relay | 5 | 5/5 | 27.2 s | 27.8 s | 26.1 s | 26.9 s |
| 4 | `dlq-post-ack` | erp-core | 5 | 5/5 | 45.3 s | 45.7 s | 43.8 s | 45.0 s |

What each passed run established (measured, per record):

1. **Inbox INSERT blocked before commit.** At the kill: 0 inbox rows, no
   reallocation, committed offset equal to the injected record's offset. After
   restart: 1 inbox row, exactly one reallocation (one `RESERVE` ledger row,
   reserved = allocated = 10, order `RESERVED`, one `BackorderReallocated`
   event) and the committed offset past the record. A republished duplicate of
   the same `event_id` was then consumed and left all of that unchanged.
2. **Inbox + business update committed, offset not committed.** At the kill:
   1 inbox row and one reallocation already committed, offset still at the
   record. After restart the record was delivered again, the new JVM's
   `event_duplicate_total` was 1 in all 5 runs, and the state stayed at one inbox
   row and one reallocation while the offset advanced.
3. **Kafka ACK, `PUBLISHED` update not committed.** At the kill: row `CLAIMED`
   on attempt 1 and the record already readable at offset 0 of its isolated
   topic. After restart and lease expiry: `PUBLISHED` on attempt 2 and the same
   `event_id` at offsets 0 and 1, i.e. at-least-once delivery, not exactly-once.
4. **DLQ ACK, source offset not committed.** At the kill: one DLQ record for the
   event, 0 inbox rows, source offset still at the record. After restart: two DLQ
   records for the event (one per delivery) and the source offset past the
   record, in all 5 runs. Nothing was dropped and the partition did not stall.

The recorded `blocked_statement` confirms where each JVM was parked: the inbox
`INSERT` (window 1), the relay's `UPDATE outbox_events SET status = 'PUBLISHED'`
(window 3) and the fault-injection gate's `SELECT 1 FROM pg_advisory_xact_lock($1)`
(windows 2 and 4).

## Findings

- **Consumer recovery is bounded by the Kafka session timeout, not by restart.**
  Restarting the container took 0.26–0.71 s and erp-core's Spring context started
  in 6.1–11.5 s (measured, Core startup log lines). The three erp-core windows still
  recovered in 42.4–47.0 s. erp-core's consumer uses the classic group protocol with
  `session.timeout.ms = 45000`, `heartbeat.interval.ms = 3000` and no
  `group.instance.id` (measured from Core's logged `ConsumerConfig`). The restarted
  consumer joins as a new member, so the coordinator reassigns the partition only
  after the killed member's session expires. This led to ADR 0011 and the
  re-measurement below: p50 45.3 s → 10.7 s with a 10 s session timeout.
- **Relay recovery is bounded by the claim lease.** With `claim-timeout: 30s`, the
  republish happened 26.1–27.8 s after the kill. The drill reads the first broker
  record before killing, which uses part of the lease.
- **The DLQ duplicate is deterministic.** Every window-4 run produced exactly two
  DLQ records per event. Operators must deduplicate DLQ review by original
  topic/partition/offset, as [the consumer-recovery runbook](../../runbooks/consumer-recovery.md)
  already says.
- **Redelivery passed back through the gate.** erp-core logged the gate 24 times:
  twice for each of the 12 gated runs (2 shakedown + 10 matrix), once while parked
  and once for the redelivered record after restart, which passed straight through
  because the drill had already released the lock (derived from the retained local
  Core log).

## Method

The drill is `tests/failure/process-kill-recovery.py` with the `fault_matrix`
package next to it; [its README](../../../tests/failure/README.md) explains the
gates. In short, the drill holds a PostgreSQL advisory lock, injects one event,
waits until an application session queues behind that lock in `pg_locks`, records
the state, sends `SIGKILL` through `docker kill`, checks the database state again,
releases the lock, drops any fixture trigger and runs `docker start`. It then polls
for the invariant with a 120 s limit. Windows 1 and 3 park the JVM with an
event-specific fixture trigger inside its own transaction. Windows 2 and 4 use
erp-core's `ConsumerCrashWindowGate`, which exists only under the `fault-injection`
profile. The matrix ran round-robin (1, 2, 3, 4, 1, 2, …) so each window's runs
are spread over the 16.8-minute session.

Windows 1 and 2 seed a fresh `BACKORDERED` order per run (10 units of the synthetic
cement SKU at a fresh location, with a converted procurement suggestion and a
received purchase order) and publish an `InventoryReceived` event for that purchase
order, so the consumer runs the real backorder reallocation. Window 4 publishes an
envelope without `organizationId`, which `DomainEventInboxApplicationService` rejects
as a permanent `InvalidEventEnvelopeException`.

`recovery_ms` is an observed upper bound. Database probes repeat every 250 ms. Each
committed-offset probe is a `kafka-consumer-groups.sh` call of about 2.5–3 s, so
offset-bound invariants can be observed up to one probe later than they occurred.
`recovery_ms` also includes the drill's release-and-restart step (`restart_ms`,
median about 0.3 s), which stands in for a supervisor restart.

## Environment

[`environment.json`](environment.json) (measured):

- Apple M3 Pro, 12 logical CPUs, 36 GiB, macOS 27.0.1. Docker Desktop 29.8.0 VM
  with 12 CPUs / 11.7 GiB, shared with three other agents' builds and tests during
  the session. Compose 5.5.1.
- Project `mf-fault-matrix` from `compose.yml` + `qualification.yml` +
  `fault-injection.yml` with loopback-only ports moved to the 4xxxx range and
  `MATERIALFLOW_SECURITY_ENABLED=false` (the drill makes no API calls; this lets it
  read Core's Prometheus counter). Only the services the drill touches were started:
  PostgreSQL 17.11, Redis 8.2.1, Kafka 4.1.2 (single broker, one partition per
  topic), `kafka-init`, erp-core and event-relay. Without an OpenTelemetry collector,
  Core logs span-export errors; they do not affect consumption.
- App images `materialflow-qualification/{erp-core,event-relay}:fault-matrix` built
  by `tests/deployment/build-runtime-images.sh` from the JARs of the passing
  `mvn -B -ntp -Pquality verify` run. The JAR hashes inside the running containers
  match those builds. Each app has 2 CPUs / 1 GiB, restart policy `no`, JVM 21.0.13.1.
- Drill on Python 3.15.0; build on JDK 21.0.13.1 and Maven 3.9.16.

[`post-run-state.json`](post-run-state.json) was captured after the matrix (measured):
no fixture triggers, functions or lock holders, no advisory-lock waiters, all 19
outbox rows `PUBLISHED`, consumer-group lag 0 (38/38), 12 DLQ records, 26 inbox rows
and 13 `RESERVE` ledger rows for the 13 reallocated fixture orders. These totals
include the shakedown attempts. The 14th fixture order is still `BACKORDERED`
because attempt 1 was stopped after seeding it.

## Session-timeout re-measurement (ADR 0011)

The same drill re-ran windows 1, 2 and 4 five times each on images built from the
ADR 0011 change (`fault-matrix-detect` tag; event-relay JAR unchanged, erp-core JAR
SHA-256 `729714d7…`, see [`environment-session-10s.json`](environment-session-10s.json)).
First with the new default (10 s session timeout, 3 s heartbeat, dynamic
membership), then with erp-core recreated through
[`static-membership.yml`](static-membership.yml), which gives the single container a
fixed `group.instance.id` that survives `docker start`. Each run directory has the
effective settings as logged by Core's `ConsumerConfig` (`consumer-config.json`).
The 20-kill matrix above is the baseline. Same host, Compose project, stack subset
and drill code; the re-measurement ran 2026-11-24 09:03–09:12 UTC.

| Window | Baseline: Kafka default 45 s | 10 s, dynamic membership | 10 s + static membership |
|---|---:|---:|---:|
| 1 `consumer-pre-commit` | 45.2 s (43.8–47.0) | 10.7 s (9.0–11.5) | 8.7 s (8.6–9.5) |
| 2 `consumer-post-commit` | 45.3 s (42.4–46.0) | 7.9 s (7.8–11.4) | 7.9 s (7.9–9.9) |
| 4 `dlq-post-ack` | 45.3 s (43.8–45.7) | 11.1 s (9.3–11.5) | 9.4 s (9.4–9.9) |
| All 15 consumer kills, p50 | 45.3 s | 10.7 s | 9.2 s |
| SIGKILLs (exit 137) / passed | 15 / 15 | 15 / 15 | 15 / 15 |
| Records | [`matrix/`](matrix) | [`session-10s/`](session-10s) | [`session-10s-static/`](session-10s-static) |

Cells are p50 (min–max) recovery over 5 runs: measured per run, p50 derived by
nearest rank. Every run kept the window's invariant: one inbox row and one
reallocation (windows 1 and 2), exactly one absorbed duplicate in the restarted JVM
(window 2), and two DLQ records with the source offset advanced (window 4). No kill
was an OOM kill, and container restarts took 0.23–0.39 s.

The 10 s default cut consumer recovery by a factor of about 4 (derived). Recovery is
now close to a plain restart: in the static-membership run Spring started in
5.9–6.8 s (measured from Core's startup log lines). The 10 s dynamic run's startup
lines were lost when Compose recreated the container for the static variant. Static
membership removed the remaining wait for the dead member's session and narrowed
the spread. Its p50 gain of about 1.5 s is smaller than the roughly 3 s resolution of
the offset probes, so it is not claimed as a measured saving. Spring Kafka suffixes
the listener container index, so the configured `erp-core-0` registered as
`erp-core-0-0`, stable across every restart.
[`post-run-state-session-10s.json`](post-run-state-session-10s.json) shows the
stack clean after both variants: no fixture triggers, functions, lock holders or
waiters, all outbox rows `PUBLISHED` and consumer-group lag 0 (60/60).

## Every attempt is kept

| Attempt | Kills | Outcome | Records |
|---|---:|---|---|
| 01 shakedown, 1 × each window | 1 | Window 1 **failed**: drill defect, stopped by hand | [`attempts/01-shakedown/kills.jsonl`](attempts/01-shakedown/kills.jsonl) |
| 02 shakedown, 1 × each window | 4 | 4/4 passed | [`attempts/02-shakedown/`](attempts/02-shakedown) |
| Matrix, 5 × each window | 20 | 20/20 passed | [`matrix/`](matrix) |
| 10 s session, windows 1, 2, 4 × 5 | 15 | 15/15 passed | [`session-10s/`](session-10s) |
| 10 s session + static membership, windows 1, 2, 4 × 5 | 15 | 15/15 passed | [`session-10s-static/`](session-10s-static) |

Attempt 1 read committed offsets from a long-lived `__consumer_offsets` console
consumer. That console consumer block-buffers its output when piped, so the drill
never saw the commit and timed out after 120 s. Meanwhile PostgreSQL showed the inbox
row and the reallocation committed 42 s after the kill, and `kafka-consumer-groups.sh`
showed the group's committed offset at 2. The watcher was replaced by direct
`kafka-consumer-groups.sh` probes (the committed code). The run was stopped while
window 2 was waiting, before its gate was taken, so attempt 1 has no `summary.json`.
In total 55 SIGKILLs were sent (25 in the first session, 30 in the re-measurement),
all with exit code 137.

`raw/` (git-ignored, kept locally) holds the drill's progress logs and the
compressed erp-core and event-relay container logs.

## Reproduce

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn -B -ntp -Pquality verify
python3 -m unittest discover -s tests/failure -p 'test_*.py'
bash tests/deployment/build-runtime-images.sh fault-matrix
sed -E -e 's/^COMPOSE_PROJECT_NAME=.*/COMPOSE_PROJECT_NAME=mf-fault-matrix/' \
  -e 's/^MATERIALFLOW_SECURITY_ENABLED=.*/MATERIALFLOW_SECURITY_ENABLED=false/' \
  -e 's/=2([0-9]{4})$/=4\1/' deploy/compose/qualification.env > /tmp/mf-fault-matrix.env
echo QUALIFICATION_TAG=fault-matrix >> /tmp/mf-fault-matrix.env
compose() { docker compose --env-file /tmp/mf-fault-matrix.env -p mf-fault-matrix \
  -f deploy/compose/compose.yml -f deploy/compose/qualification.yml \
  -f deploy/compose/fault-injection.yml "$@"; }
compose up -d --no-build postgres redis kafka kafka-init erp-core event-relay
python3 tests/failure/process-kill-recovery.py --project mf-fault-matrix \
  --windows all --repeat 5 --output-dir /tmp/mf-fault-matrix-NEW
compose down -v
```

For the ADR 0011 re-measurement, build `fault-matrix-detect` images from the current
tree, set `QUALIFICATION_TAG=fault-matrix-detect` in the env file and run
`--windows 1,2,4 --repeat 5` twice: once as above, then after
`compose -f docs/evidence/local-2026-11-23-fault-matrix/static-membership.yml up -d
--no-build erp-core`.

## Limits

- One single-broker Kafka with one partition per topic, one erp-core consumer and
  one relay instance on one Docker host. Five runs per window give a small sample,
  and the timings come from a shared workstation. No multi-node, network-partition,
  broker-failure or production-scale claim.
- Recovery times depend on the consumer's session timeout (45 s in the baseline,
  10 s after ADR 0011) and the relay's 30 s lease. They describe these
  configurations, not a recovery SLA.
- Windows 2 and 4 rely on a test-only hook. It is profile-gated and proven inert by
  `ConsumerCrashWindowGateTest`. `DomainEventConsumerRecoveryIntegrationTest` shows
  that it runs after the side effect is durable and before the offset commit. The
  kill itself is a real JVM SIGKILL.
- At-least-once holds at every boundary tested: Kafka may hold the same `event_id`
  twice and the DLQ may hold a record twice. Only the consumer inbox makes the
  business effect happen once. None of this is exactly-once delivery.
- This matrix is separate from the fixed 1,200-delivery replay and from the
  September 7 two-kill qualification. Its counts belong to it alone.
