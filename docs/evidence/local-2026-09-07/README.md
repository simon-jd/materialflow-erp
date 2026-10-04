# Local consumer recovery verification — 2026-09-07

The update adds exception-specific Kafka consumer recovery to the existing
synthetic Java ERP. It preserves the prior uncommitted Relay work. Everything here
is **local_only**; there is no remote, new deployment, hosted CI or production claim.

## Results

| Check | Observed result |
|---|---|
| Existing baseline, before implementation | 45 JUnit tests passed |
| Final clean Maven reactor | **53 tests / 21 suites; 0 failures, errors or skips** |
| New focused cases | 4 unit tests and 4 real Kafka/PostgreSQL integration tests passed |
| Old uniform retry selection, isolated negative control | Same lock-retention assertion failed as expected; 1 test, 1 assertion failure, 0 errors |
| Checkstyle and SpotBugs | Passed |
| Promtool | 5 scenario groups / 10 alert assertions passed |
| JaCoCo | 79.07% across emitted report scope only; not whole-reactor coverage |
| Historical benchmark artifacts | All three prior JTL hashes match; no new benchmark or performance comparison |

The database case asserts actual server `lock_timeout=200ms`, holds an ACCESS
EXCLUSIVE table lock, observes at least six failed deliveries (beyond the old
budget), and checks no DLQ record or source-offset advancement. Releasing the lock
allows one Inbox row and the next source offset to commit. This is an assertion
threshold, not a measured recovery-latency statistic.

Other cases verify malformed-input quarantine followed by valid progress, rollback
of an Inbox insert before invalid InventoryReceived identity quarantine, and
missing-DLQ offset retention across a real consumer stop/recreate with the same
group. After provisioning the topic, the retained poison record is quarantined and
the following valid event commits. The production factory/listener and proxied
Inbox run inside JUnit; PostgreSQL/Kafka are actual local containers. No JVM was
killed and no actual connection-pool exhaustion was induced.

## Evidence and reproduction

- [Test inventory](test-results.json): final case names, counts, outcomes and command.
- [Verification record](verification.json): gate results, development failures,
  raw-log hashes/paths, coverage scope and checks not run.
- [Execution environment](environment.json): host, tools, dependency versions,
  container image IDs and fault topology.
- [Source manifest](source-manifest.json) and [initial manifest](baseline-manifest.json):
  distinguish final source from the state inherited at turn start.
- [Negative-control result](negative-control.json): exact isolated mutation and
  failed assertion. In a temporary source copy, remove only the documented
  `handler.setBackOffFunction(...)` line, then run its listed Maven command.
  This restores old retry selection; it is not a historical checkout.
- [Historical artifact integrity](historical-artifact-check.json): hash check only.
- [Research](../../research/2026-09-07-consumer-reliability.md),
  [ADR](../../adr/0006-consumer-failure-classification.md) and
  [runbook](../../runbooks/consumer-recovery.md).
- [Bank synchronization record](bank-sync.json): backup, concurrency checks,
  before/after hashes and reread validation (written after synchronization).

```bash
mvn -B -ntp clean verify
mvn -B -ntp -Pquality -DskipTests verify
```

This machine used `-o -Dmaven.repo.local=/tmp/materialflow-m2` with existing cached
dependencies. Another machine may need normal Maven dependency resolution and
Docker image pulls. Raw development/test logs and temporary scripts stay in
`/tmp/materialflow-sep07`, outside the project, per the supplied Generator.
The retained inventories/hashes and source tests support reproduction; temporary
logs are not promised permanent retention.

## Findings and limits

The first fixture attempts did not reliably establish the lock timeout. Inspection
found a real blocked INSERT; the final fixture sets the server timeout and asserts
it. Once that fault was effective, Spring JDBC returned UncategorizedSQLException
for PostgreSQL SQLSTATE 55P03. The classifier was corrected using the stable code,
with regression checks excluding schema/constraint failures. These failed attempts
are recorded separately and are not counted as passing verification.

The default library already awaited DLQ publication; explicitly retaining that
setting and verifying it is not a newly fixed async-loss bug. RECORD mode owns
successful-recovery offsets; the removed `commitRecovered` setting was meaningful
only for MANUAL_IMMEDIATE. Quarantine and business success remain different facts.

Source replay depends on retention. Persistent failures can block assigned work;
retry-delay caps do not cap SQL execution time. A crash between DLQ ACK and source
offset commit can duplicate DLQ records. No cross-system exactly-once guarantee,
performance improvement, cloud SLA, production incident, supported-framework
migration or fresh image security/deployment qualification is claimed.
