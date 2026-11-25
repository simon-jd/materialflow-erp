# Process-kill fault matrix on AKS, 2026-11-24

The [local fault matrix](../local-2026-11-23-fault-matrix/README.md) SIGKILLed the erp-core and
event-relay JVMs inside four crash windows of the outbox → Kafka → consumer-inbox path on Docker.
This run takes the same drill to the AKS environment
([platform evidence](../azure-2026-11-24-platform/README.md)): two replicas of each app on
Arm64 burstable nodes, Kafka in the cluster, PostgreSQL on the managed Flexible Server, and the
kubelet, not the drill, restarting the killed container. All data is synthetic. Labels:
**measured** values come straight from the retained records, **derived** values are computed
from them.

## Result

20 real SIGKILLs (4 windows × 5) on AKS, 20 passed, 2026-11-24 20:10:48–21:16:42 UTC. Every kill
ended the container with exit code 137 (reason `Error`, never `OOMKilled`), and every restart was
in place: same pod and IP, restart count + 1, new container ID, new `startedAt`, new JVM PID
(measured, [`matrix/kills.jsonl`](matrix/kills.jsonl), [`matrix/summary.json`](matrix/summary.json)).
p50 is nearest rank over 5 runs (derived).

| # | Window | Killed | SIGKILLs (exit 137) | Passed | Recovery p50 (min–max) | Kill → container running p50 (min–max) | Kill → Ready p50 (min–max) | Recovered by |
|---|---|---|---:|---:|---:|---:|---:|---|
| 1 | `consumer-pre-commit` | erp-core | 5 | 5/5 | 15.8 s (14.7 s–16.4 s) | 302.0 s (10.4 s–325.4 s) | 321.0 s (30.6 s–344.3 s) | other replica 5 |
| 2 | `consumer-post-commit` | erp-core | 5 | 5/5 | 14.0 s (9.8 s–14.2 s) | 306.6 s (23.1 s–320.9 s) | 323.1 s (40.8 s–337.9 s) | other replica 5 |
| 3 | `relay-post-ack` | event-relay | 5 | 5/5 | 17.8 s (17.0 s–19.0 s) | 1.1 s (0.5 s–1.9 s) | 14.5 s (13.8 s–17.4 s) | n/a (relay) 5 |
| 4 | `dlq-post-ack` | erp-core | 5 | 5/5 | 13.8 s (13.7 s–14.1 s) | 160.9 s (0.5 s–312.9 s) | 178.6 s (19.6 s–331.0 s) | other replica 5 |

What each passed run established (measured, per record), exactly as in the local matrix:

1. **Inbox INSERT blocked before commit.** At the kill: 0 inbox rows, no reallocation, committed
   offset at the injected record. After recovery: 1 inbox row, one reallocation (one `RESERVE`
   ledger row, reserved = allocated = 10, order `RESERVED`, one `BackorderReallocated` event),
   offset past the record; a republished duplicate changed nothing.
2. **Inbox + business committed, offset not.** Redelivered after the kill, absorbed by the inbox
   key: `event_duplicate_total` rose by exactly 1 across the erp-core JVMs in all 5 runs, still one
   reallocation, offset advanced.
3. **Kafka ACK, `PUBLISHED` not committed.** After lease expiry `PUBLISHED` on attempt 2 and the same
   `event_id` at offsets 0 and 1 of the isolated topic (at-least-once).
4. **DLQ ACK, source offset not committed.** Two DLQ records for the event and the source offset
   advanced in all 5 runs.

[`post-run-state.txt`](post-run-state.txt): no fixture triggers, functions, lock holders or
advisory-lock waiters; all 24 outbox rows `PUBLISHED`; consumer-group lag 0 (52/52); 12 DLQ
records (2 shakedown + 10 matrix); the 14 fixture orders all `RESERVED`. The five
`materialflow.qualification.*` topics were deleted afterwards and the fault switch turned off
(Helm revision 8: `shareProcessNamespace` and the profile gone from the new pods).

## Findings

- **With two replicas, recovery does not wait for the restart.** In all 15 erp-core kills the
  partition owner at the moment of recovery was the *other* replica (measured: the group's HOST
  column). The survivor got the partition once the killed member's 10 s session expired; consumer
  recovery took 9.8–16.4 s (measured), while the killed container was running again only after
  0.5–325 s and Ready after 19.6–344 s. Run 16 (restart after 0.5 s) recovered in 14.1 s and run 13
  (restart after 325 s) in 15.8 s: the kubelet back-off is visible in the restart columns and
  absent from recovery. ADR 0011's 10 s session timeout, not the container restart, bounds consumer
  recovery here.
- **The kubelet back-off counts from the exit, not from the last start.** The same erp-core pod
  owned the partition after every rejoin in runs 1–15, so it was killed 11 times in 53 minutes:
  restarted after 51, 94, 161 s, then 302–325 s (CrashLoopBackOff in between), although it had
  run for several minutes before each kill. A different pod killed for the first time in more
  than 10 minutes restarted after 0.5 s (run 16), then 10, 23 and 55 s. The 10 s → 300 s
  doubling applies until 10 minutes pass between two exits; how long the container ran in between
  does not matter. Restarts came up to about 25 s after the nominal back-off (kubelet sync). The
  runbook and the chart comment, which said otherwise, were corrected.
- **The relay window is bounded by its 30 s lease from the first claim.** Claim → republish took
  30.2–31.8 s on the database clock in all 5 runs (measured). Kill → recovered was 17.0–19.0 s
  because the drill, working through the API server, spends 12–15 s between the claim and the kill
  (reading the first broker record, the parked checks). Locally that gap was about 3 s, hence
  26.1–27.8 s there. Same mechanism, different drill overhead.
- **JVM start on the Arm64 burstable nodes:** container running → Ready took 12–20 s for both
  apps (derived from the two restart columns), comparable with the 6–12 s Spring start locally
  plus the readiness probe period.
- **Kafka CLI heap.** The broker container runs with a 512 MiB heap and a 1 GiB limit, and the CLI
  scripts inherit `KAFKA_HEAP_OPTS`; the drill gives each CLI call `-Xms32m -Xmx256m`. kafka-0 did
  not restart during the 26 drill kills.

## Method

`tests/failure/process-kill-recovery.py --backend kubernetes` (code in
[`tests/failure/fault_matrix/`](../../../tests/failure/fault_matrix), described in
[tests/failure/README.md](../../../tests/failure/README.md#run-it-on-kubernetes-aks)). The windows
and their invariants are unchanged from the local matrix; only the backend differs:

- **Opt-in.** The application chart's `faultInjection.enabled=true` (Helm revision 7) gives
  erp-core and event-relay `shareProcessNamespace: true`, erp-core the `fault-injection` Spring
  profile and both the label `materialflow.io/fault-injection=enabled`. The drill refuses pods
  without that label and needs the kube context by name.
- **Parking.** Same as locally: an advisory lock held by a `psql` session in the toolbox pod,
  with an event-specific trigger for windows 1 and 3 and erp-core's crash-window gate for
  windows 2 and 4. The recorded `blocked_statement` is the inbox `INSERT` (window 1), the relay's
  `UPDATE outbox_events SET status = 'PUBLISHED'` (window 3) and the gate's
  `SELECT 1 FROM pg_advisory_xact_lock($1)` (windows 2 and 4) in every run.
- **Which pod.** With two replicas the drill kills the JVM that is actually parked: for erp-core
  the consumer-group member owning the domain topic's single partition (group HOST = pod IP),
  for event-relay the pod in the outbox row's `claimed_by`.
- **Kill.** `kubectl exec <pod> -c <app> -- kill -KILL <pid of java>`. With the shared PID
  namespace the pause container is PID 1; the JVM had PID 6 or 7 after a pod start and a new,
  higher PID after every in-place restart (recorded as `pid_before`/`pid_after`).
- **Restart evidence.** From `containerStatuses`: `lastState.terminated.exitCode == 137`, reason
  `Error` (not `OOMKilled`), restart count + 1, a new container ID and a new `startedAt`. A
  background poller (`kubectl get pod`, one reading about every 0.4 s) timestamps the first
  reading with the restarted container running and the first with it Ready.
- **Three times per run** (measured, drill machine's monotonic clock, from the moment the kill
  exec returned): kill → container running, kill → container Ready, kill → window invariant
  observed (`recovery_ms`). They overlap: recovery does not wait for the restart.
- **Between runs** the drill waits until the killed container is Ready again and, after an
  erp-core kill, until the consumer group is `Stable` with both members, so every run starts
  with two Ready replicas of each app.
- **Resolution.** Every probe goes through the AKS API server from the operator machine: a
  `psql` probe takes about 0.8 s and a `kafka-consumer-groups.sh` describe about 4 s
  (measured), against 0.25 s and 2.5–3 s locally. Offset-bound invariants (all erp-core
  windows) can therefore be observed up to about 4 s after they occurred.
- Session timeout 10 s, heartbeat 3 s, dynamic membership (ADR 0011 defaults); relay claim lease
  30 s; erp-core and event-relay images `b20261124-1`
  ([environment](../azure-2026-11-24-cloud-tests/environment.json)).

## Kubelet crash back-off

Restart delays of the erp-core pod that owned the partition (measured, kill → container
running), in kill order: 3.4, 11.3, 25.9 s in the shakedown, then 50.7, 94.4, 160.9, 312.7, 320.9,
312.9, 302.0, 306.6, 303.3, 325.4, 316.7 s in the matrix. That is 10 s doubling per exit
(10, 20, 40, 80, 160, 300) plus a few to 25 s until the kubelet's next sync of the pod. The second
erp-core pod: 0.5, 10.4, 23.1, 55.1 s. The relay pods were killed at most twice within 10 minutes:
0.5–1.9 s, except 10.9 s in the shakedown for a relay pod that had been killed by hand a few minutes before.

The drill was not spaced to avoid the back-off: avoiding it would need 10 minutes between kills
of the same pod, and with one partition the same pod kept getting it back. Instead the matrix
waits for the killed container to be Ready before the next run (which is why it took 66 minutes)
and records the delay. Its effect on availability is nil with two replicas (above); with one
replica, consumer recovery would have been bounded by these delays.

## Every attempt is kept

| Attempt | Kills | Outcome | Records |
|---|---:|---|---|
| 01 shakedown, 1 × each window | 1 | Window 1 **failed** on a drill defect after its invariant was observed (recovery 16.6 s); stopped by hand | [`attempts/01-shakedown/kills.jsonl`](attempts/01-shakedown/kills.jsonl) |
| 02 shakedown, 1 × each window | 4 | 4/4 passed | [`attempts/02-shakedown/`](attempts/02-shakedown) |
| Matrix, 5 × each window | 20 | 20/20 passed | [`matrix/`](matrix) |

Attempt 1 found two drill defects: `recovered()` referenced the stack without receiving it
(`NameError`, so the window failed after recovery had been measured), and the first restart
timer used `kubectl get pod --watch`, which exited as soon as the pod changed, so the drill
waited 7 minutes for a Ready it could no longer see. The timer became the poller described
above and a unit test now covers `recovered()`. Stopping attempt 1 left one gate-holder session
(`pg_sleep`), which was terminated by hand before attempt 2. Between the attempts one relay JVM
was killed once by hand to reproduce the watch problem. In total 26 drill SIGKILLs and that one
manual SIGKILL were sent; all exited 137.

Per run (measured):

| Run | Window | Kill (UTC) | Pod | Restarts before | Kill → running | Kill → Ready | Kill → recovered | Waiting reason | Notes |
|---:|---|---|---|---:|---:|---:|---:|---|---|
| 1 | 1 | 20:11:15 | `…hrvzj` | 3 | 50.7 s | 68.7 s | 16.4 s | CrashLoopBackOff |  |
| 2 | 2 | 20:12:57 | `…hrvzj` | 4 | 94.4 s | 111.7 s | 14.2 s | CrashLoopBackOff | duplicates absorbed 1 |
| 3 | 3 | 20:15:16 | `…8bc9t` | 0 | 0.5 s | 14.5 s | 17.8 s | — | claim → republished 31.5 s |
| 4 | 4 | 20:16:20 | `…hrvzj` | 5 | 160.9 s | 178.6 s | 13.8 s | CrashLoopBackOff |  |
| 5 | 1 | 20:19:48 | `…hrvzj` | 6 | 312.7 s | 330.3 s | 16.4 s | CrashLoopBackOff |  |
| 6 | 2 | 20:25:51 | `…hrvzj` | 7 | 320.9 s | 337.9 s | 9.8 s | CrashLoopBackOff | duplicates absorbed 1 |
| 7 | 3 | 20:31:55 | `…8bc9t` | 1 | 1.1 s | 15.3 s | 17.7 s | — | claim → republished 30.2 s |
| 8 | 4 | 20:32:58 | `…hrvzj` | 8 | 312.9 s | 331.0 s | 13.8 s | CrashLoopBackOff |  |
| 9 | 1 | 20:39:08 | `…hrvzj` | 9 | 302.0 s | 321.0 s | 14.7 s | CrashLoopBackOff |  |
| 10 | 2 | 20:45:01 | `…hrvzj` | 10 | 306.6 s | 323.1 s | 14.2 s | CrashLoopBackOff | duplicates absorbed 1 |
| 11 | 3 | 20:50:50 | `…5g5m9` | 2 | 0.9 s | 14.0 s | 19.0 s | — | claim → republished 31.5 s |
| 12 | 4 | 20:51:55 | `…hrvzj` | 11 | 303.3 s | 319.5 s | 13.7 s | CrashLoopBackOff |  |
| 13 | 1 | 20:57:44 | `…hrvzj` | 12 | 325.4 s | 344.3 s | 15.8 s | CrashLoopBackOff |  |
| 14 | 2 | 21:04:02 | `…hrvzj` | 13 | 316.7 s | 336.6 s | 14.0 s | CrashLoopBackOff | duplicates absorbed 1 |
| 15 | 3 | 21:10:07 | `…5g5m9` | 3 | 1.9 s | 17.4 s | 17.0 s | — | claim → republished 31.7 s |
| 16 | 4 | 21:11:10 | `…w9g6j` | 1 | 0.5 s | 19.6 s | 14.1 s | — |  |
| 17 | 1 | 21:12:04 | `…w9g6j` | 2 | 10.4 s | 30.6 s | 15.8 s | CrashLoopBackOff |  |
| 18 | 2 | 21:13:09 | `…w9g6j` | 3 | 23.1 s | 40.8 s | 10.3 s | CrashLoopBackOff | duplicates absorbed 1 |
| 19 | 3 | 21:14:17 | `…8bc9t` | 2 | 1.4 s | 13.8 s | 18.3 s | — | claim → republished 31.8 s |
| 20 | 4 | 21:15:21 | `…w9g6j` | 4 | 55.1 s | 73.3 s | 13.8 s | CrashLoopBackOff |  |

## Comparison with the local Docker matrix

| Window | Local Docker, 10 s session ([evidence](../local-2026-11-23-fault-matrix/README.md#session-timeout-re-measurement-adr-0011)) | AKS, 10 s session, 2 replicas |
|---|---:|---:|
| 1 `consumer-pre-commit` | 10.7 s (9.0–11.5) | 15.8 s (14.7–16.4) |
| 2 `consumer-post-commit` | 7.9 s (7.8–11.4) | 14.0 s (9.8–14.2) |
| 3 `relay-post-ack` (lease-bound; local value from the 45 s baseline matrix, unaffected by the session timeout) | 27.2 s (26.1–27.8) | 17.8 s (17.0–19.0); claim → republish 30.2–31.8 s |
| 4 `dlq-post-ack` | 11.1 s (9.3–11.5) | 13.8 s (13.7–14.1) |
| Who restored the invariant | the restarted container (one replica) | the other replica, 15 of 15 consumer kills |
| Kill → container running | 0.23–0.39 s (`docker start` by the drill) | 0.5–325 s (kubelet, crash back-off) |
| SIGKILLs / passed | 15 / 15 (consumer windows) + 5 / 5 relay in the baseline | 20 / 20 |

Cells are p50 (min–max) over 5 runs. The consumer windows took 3–6 s longer at p50 on AKS
(derived). Part of that is measurement: every offset probe takes about 4 s through the API server
against 2.5–3 s locally, and the invariant can only be seen when a probe returns. The rest is the
rebalance onto a member that was idle rather than a restarted JVM that rejoined. The data do not
separate the two. Correctness did not change: the same invariants held in all 20 runs.

## Reproduce

```bash
export KUBECONFIG=~/materialflow-azure/kubeconfig U=https://materialflow-erp.westus.cloudapp.azure.com
helm upgrade materialflow deploy/helm/materialflow -n materialflow --reuse-values --set faultInjection.enabled=true --wait
umask 077; curl -fsS "$U/idp/realms/materialflow/protocol/openid-connect/token" -d client_id=materialflow-cli \
  -d grant_type=password -d username=synthetic-demo -d password=synthetic-demo-only | jq -r .access_token > /tmp/mf-token
python3 tests/failure/process-kill-recovery.py --backend kubernetes --kube-context aks-materialflow-dev \
  --token-file /tmp/mf-token --windows all --repeat 5 --output-dir /tmp/mf-fault-matrix-aks-NEW
helm upgrade materialflow deploy/helm/materialflow -n materialflow --reuse-values --set faultInjection.enabled=false --wait
```

## Limits

- Five runs per window, one cluster, one afternoon. Two replicas of each app, one Kafka broker
  with one partition per topic: with one partition only one erp-core replica consumes at a time.
- Probe resolution is coarse (above); recovery times are observed upper bounds.
- The burstable nodes had CPU credits throughout (they were not throttled to the baseline); a
  credit-starved node would start JVMs more slowly.
- Windows 2 and 4 rely on the profile-gated test hook, as locally. The kill is a real SIGKILL.
