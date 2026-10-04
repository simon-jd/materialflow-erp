# Local reliability verification — 2026-09-06

MaterialFlow remains `implemented_local_reference`, using synthetic business
data. This update fixes two reproduced Outbox ownership/recovery bugs and makes
consumer success metrics reflect committed work. It does not establish a new
performance result, production experience, an ML-serving system or cloud SLA.

## What changed and what passed

| Check | Observed result | Evidence |
|---|---|---|
| Full clean Maven reactor | 45 tests in 19 suites; 0 failures/errors/skips | [Test results and case names](test-results.json), [compressed log](verify.log.gz) |
| Relay-focused suite | 17 tests pass, including real PostgreSQL and Kafka | [Compressed Relay log](relay-verify.log.gz) |
| Old-code negative control | 3 tests, 2 expected failures: stranded final claim and same-name stale ACK | [Original test overlay](negative-control-test.java.txt), [compressed log](negative-control.log.gz) |
| Static quality profile | Checkstyle and SpotBugs exit 0 across the reactor | [Compressed quality log](quality.log.gz) |
| Prometheus rules | Two scenarios pass: retained dead letter despite zero transition counter, and cleared state | [Test fixture](../../../deploy/compose/prometheus/alerts.test.yml), [command record](verification.json) |
| Compose / dashboard / diff | Compose config, dashboard JSON and whitespace check pass | [Command record](verification.json) |
| Prior performance artifacts | Recomputed 3 archived JTLs: 36,009 requests, 0 errors; per-run percentile results and hashes match | [Historical recheck](historical-performance-recheck.json) |

See the [environment](environment.json), [tested source hashes](source-manifest.json),
[architecture decision](../../adr/0005-outbox-claim-lifecycle.md) and
[research/maintenance review](../../research/2026-09-06-market-and-maintenance.md).
The existing local Git HEAD is recorded in the manifest. New work is uncommitted;
no new remote permalink, hosted CI run or published release is claimed.

JaCoCo reports cover 1,471 lines and miss 443 (76.85%) across 11 emitted reports.
This excludes modules with no coverage report and is not a reactor-wide coverage
percentage. Test quantity/coverage are verification inventory, not evidence of
production reliability or performance improvement.

## Failure boundaries actually exercised

1. Two Relay callers claim 12 disjoint rows in real PostgreSQL. Locked rows are
   skipped while another actual database transaction holds the lock.
2. A stale completion cannot update a successor claim even with the same instance
   name or a reset attempt count. Failed/stale completions cannot inflate the
   published/dead-letter transition counts.
3. Expired final attempts become reviewable dead letters. Live final claims and
   future retries are not swept; each sweep is bounded. Dead-letter state means
   the outcome may be uncertain, not that delivery definitely failed.
4. With real local Kafka, inject a database failure after an actual broker ACK,
   expire the stored lease timestamp, and republish through the Relay. The broker
   yields two distinct offsets with identical event key/payload and PostgreSQL
   finishes at PUBLISHED with two attempts. No process was killed in this test.
5. The real ERP consumer allocates inventory inside a transaction that is then
   deliberately rolled back. Inbox, allocation, ledger/Outbox and success count
   remain unchanged. Twelve concurrent calls then yield one new processing
   result and eleven duplicates, with one allocation/ledger/Outbox side effect.
   A later committed replay is also a duplicate. This uses the application service
   directly; no actual Kafka offset-commit crash was executed.
6. Empty schema to V6 and V5 to V6 with an existing claim both migrate through
   the real Flyway resources. Existing event payload is preserved.
7. Unit-level controlled failures cover Kafka errors/timeouts, interrupt handling,
   stale writes and post-ACK database errors. Promtool verifies persistent-state
   alert behavior with synthetic time series; no live alert notification was sent.

## Reproduction

Requires Java 21, Maven 3.9+, Docker and access to Maven Central/container images.
Use a writable Maven cache (this run used `/tmp/materialflow-m2`):

```bash
mvn -B -ntp -Dmaven.repo.local=/tmp/materialflow-m2 clean verify
mvn -B -ntp -Dmaven.repo.local=/tmp/materialflow-m2 -Pquality -DskipTests verify
docker compose -f deploy/compose/compose.yml config --quiet
docker run --rm --network none --read-only --tmpfs /tmp:rw,nosuid,size=64m \
  --cap-drop ALL --security-opt no-new-privileges --entrypoint /bin/promtool \
  -v "$PWD/deploy/compose/prometheus:/rules:ro" -w /rules \
  prom/prometheus:v3.6.0 test rules alerts.test.yml
```

To reproduce the negative control without modifying the current source:

```bash
baseline_dir=$(mktemp -d /tmp/materialflow-negative.XXXXXX)
git --git-dir=.git-local archive 20fdf33 | tar -x -C "$baseline_dir"
cp docs/evidence/local-2026-09-06/negative-control-test.java.txt \
  "$baseline_dir/apps/event-relay/src/test/java/com/materialflow/relay/outbox/OutboxRepositoryIntegrationTest.java"
mvn -B -ntp -Dmaven.repo.local=/tmp/materialflow-m2 -f "$baseline_dir/pom.xml" \
  -pl apps/event-relay -am -Dtest=OutboxRepositoryIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected negative-control exit is nonzero with exactly the two named assertion
failures. The archived overlay uses the old API/schema; the new tests exercise
the token API and actual Flyway migrations. It is not a literal unchanged-test
comparison because the token is a new method argument.

## Limits and earlier evidence

- This is local, real PostgreSQL/Kafka with selected injected faults. No new
  application image rebuild/deployment, kind rollout, cloud provisioning,
  real-hardware integration or production drill was performed.
- Physical Relay/consumer kills, consumer offset-commit crash, event reordering,
  connection-pool exhaustion, network partitions and Flyway failure-readiness
  drills remain unverified. Do not mark the whole charter complete.
- Spring Boot 3.5.16 is past OSS support. Boot 4/matching Cloud migration and fresh
  dependency/image scanning remain open; September 1 scans do not certify this
  new source or the current advisory database.
- Historical performance was measured on the September 1 source/images, at a
  capped 10 requests/s on one host, with 3 × (10-minute warmup + 20-minute
  measurement). Its three-run median p99 is 44 ms. The raw hashes were rechecked,
  but no post-change benchmark or speedup is claimed. Just-in-time claims add DB
  round trips; throughput impact is unknown.
- The first sandboxed build failed at DNS resolution; authorized local/network
  execution succeeded. The first Promtool attempt lacked a writable `/tmp` in
  its read-only container; adding a tmpfs resolved that harness issue.
- Historical `experience_bank_created: false` and authorization statements in
  the September 1 snapshot remain historically accurate. The user authorized
  this task's new bank entry. See [bank synchronization audit](bank-sync.json)
  for backup paths, before/after hashes and post-write reference checks.
