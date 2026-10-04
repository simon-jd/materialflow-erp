# Local runtime qualification — September 7, 2026

This continuation qualifies the actual applications after the earlier consumer
reliability update. All data is synthetic and all execution is local. The work
remains uncommitted and AI-assisted; it establishes no production tenure, customer
impact, real supplier integration, cloud operation or ML/GPU capability.

## Engineering changes and rationale

The [dated official-source research](../../research/2026-09-07-runtime-qualification.md)
ties the work to early-career backend/infrastructure requirements for testing,
debugging and operational ownership. It does not infer a universal framework
requirement from a small JD sample. Boot 3.5's ended OSS maintenance and actual
runtime incompatibilities justify a coordinated, compatible Boot 4.0 family.

- Five applications now use Boot 4.0.8 / Cloud 2025.1.2, Jackson 3, MyBatis 4.0.1
  and Resilience4j 2.4.0 on Java 21. Explicit modular starters preserve Kafka,
  Flyway, security, AOP and telemetry. PostgreSQL 17.11 / Kafka 4.1.2 are qualified
  locally; Protobuf 4.33.5 fixes observed OTel gencode incompatibility.
- Actual fresh Docker startup exposed a Streams missing-topic race and Kafka 4's
  changed linger default invalidating the Relay's timeout pair. Idempotent topic
  initialization now precedes dependent startup, and the serial Relay explicitly
  uses zero linger. Recovery tests now read application Kafka configuration.
- New consumer groups explicitly replay retained events. An actual Boot 3
  persisted order hash/response replays under Boot 4 without a second order;
  no unnecessary mapper compatibility switch was added.
- Exact tested JARs are packaged into non-root, read-only runtime images. A
  loopback-only Compose overlay, process-kill fixtures and CSV-aware performance
  tooling make the operational checks repeatable.

The [decision record](../../adr/0007-boot4-runtime-qualification.md) covers costs
and rollback limits. The [runbook](../../runbooks/runtime-qualification.md) gives
reproduction commands. [Initial findings](qualification-findings.json) preserve
failed attempts; the first projection error log was lost before recreation and
is explicitly not presented as a retained raw artifact.

## Executed verification

| Check | Observed outcome | Evidence |
|---|---|---|
| Clean Maven reactor with quality profile | 56 JUnit cases in 21 suites, 0 failures/errors/skips; Checkstyle and SpotBugs passed | [Tests](test-results.json), [commands](verification.json) |
| Additional checks | 3 Python CSV-parser tests; Promtool 5 groups / 10 assertions; Helm lint/render and Compose validation | [Verification](verification.json) |
| Runtime identity | All 5 running JAR hashes match the tested builds; 2 CPU / 1 GiB per app, user 10001, read-only filesystem | [Manifest](boot4-container-manifest.json) |
| Functional deployment | All 5 health checks UP; JWT 401/201/403/404 cases; matching projection; HTTP → synthetic gRPC confirmation | [Smoke](deployment-smoke.json), [JWT](jwt-rbac.json) |
| Tracing | One exported 19-span trace across Gateway, Core, Relay and domain consumer | [Smoke](deployment-smoke.json) |
| Current image scan | 0 High/Critical findings in the 5 exact application images after Tomcat 11.0.25 patch; dated Trivy 0.74.0 databases | [Scan scope and identities](image-scan-summary.json) |
| Legacy persisted data | Actual Boot 3 order idempotency record replayed under Boot 4 without creating another order | [Compatibility](legacy-compatibility.json) |
| Actual process death | Both external SIGKILL scenarios passed with exit 137, no OOM and a new process start | [Process results](process-kill.json) |
| Recovery cleanup | All 5 apps UP again; no fixture triggers/functions/sessions, locks or unpublished Outbox rows; both domain groups at lag 0 | [Post-drill state](post-drill-state.json) |

The consumer drill blocks a unique Inbox INSERT before commit, verifies the source
offset remains 7219 across actual Core death, then restarts and republishes the
same event. The offset reaches 7221 while exactly one Inbox row exists. This probe
has no inventory effect; separate JUnit evidence covers inventory atomicity.

The Relay drill confirms Kafka delivery while its PUBLISHED database transaction
is blocked, then kills the actual JVM. Natural claim expiry and restart yield
PUBLISHED on attempt 2, with the same event at offsets 0 and 1 in an isolated
topic. This deliberately verifies at-least-once replay, not exactly-once delivery.

## Measured runtime comparison

The [protocol](performance-protocol.json) was written before measurement. Each
variant used three 60-second warmups and three 180-second measured phases, four
JMeter workers and a cap of 600 requests/minute through the JWT gateway. Supporting
images and application limits match; each variant started with fresh state.

| Metric | Pre-migration Boot 3 | Qualified Boot 4 |
|---|---:|---:|
| Measured requests | 5,409 | 5,409 |
| Errors / timeouts | 0 / 0 | 0 / 0 |
| Median run p50 | 10 ms | 10 ms |
| Median run p95 | 20 ms | 22 ms |
| Median run p99 | 33 ms | 35 ms |
| Observed request rate | 10.019 requests/s | 10.019 requests/s |

No latency improvement was measured. The shared-host, sequential, non-randomized
comparison does not establish a causal regression or speedup. The configured cap
also prevents a maximum-throughput claim. Percentiles are nearest-rank within
each run and median across runs, not pooled percentiles. Raw compressed JTLs,
warmups, resource samples, environment and database/offset observations are under
`performance/boot3` and `performance/boot4`; the [summary](performance-summary.json)
was recomputed from those retained files and includes their hashes.

After measurement, 7,219 orders/events including warmups and one security fixture
were published and consumed; both domain consumer groups had zero lag and the
database had no lock waiters. This is an [eventual drain check](performance-drain.json),
not an event-latency SLA. The historical September 1 benchmark uses a different
600/1200-second protocol and remains separate.

## Evidence preservation and remaining limits

`raw/` retains compressed clean-build logs, all 21 Surefire XML reports, final
image scan reports and relevant initial failure records. The [source manifest](source-manifest.json)
links the source/configuration snapshot and evidence artifact hashes. Baseline
JAR/image identities remain in the [old manifest](boot3-container-manifest.json).
The [bank synchronization record](bank-sync.json) records the backup, concurrency
checks, preserved IDs/schema/other projects and strict JSON/reference validation.
Qualification containers are stopped after verification; images, containers and
synthetic volumes remain available. [Cleanup state](cleanup-state.json) records
the three task-specific projects; no global prune was used.

Still unqualified: process death after Inbox commit but before offset commit,
death after DLQ ACK but before offset commit, multi-node partitions, pool
exhaustion, event-ordering stress, production-sized migrations and all historical
request shapes. No new kind rollout, cloud deployment or hosted CI run occurred.
Image scans cover High/Critical findings in application images only; lower
severities, support-service images and a full API/authorization audit are outside
this check. Emitted-report JaCoCo coverage is 78.97%, not whole-reactor coverage
and not a selected résumé metric.
