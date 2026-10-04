# Local verification index — 2026-09-01

## Outcome

MaterialFlow ERP is implemented and locally verified as a private,
portfolio-scale Java 21 reference backend. The business flows, concurrency
controls, transactional outbox/inbox, five application containers, local
observability stack, contracts, hardened Helm/kind assets, failure drills,
security scans, and the formal three-run performance baseline were executed on
the environment recorded here.

This release is `local_only` and its state is `implemented_local_reference`.
Formal local performance, Kubernetes Pod deletion/rolling update, JWT/RBAC and
cross-tenant isolation, exact final-image scans, and a single trace spanning API,
database transaction, Outbox, Kafka, and consumer are now measured. It is still
not evidence of real production employment, customers, team tenure, cloud SLA,
or incident ownership. Experience Bank content was not created because the
charter requires separate explicit authorization.

No public repository, remote push, paid cloud resource, or production deployment
was created.

The verified source baseline is
`431774cb7d75d7d09e37eaea8576dbcd4912bdea` in the local `.git-local`
metadata repository. The following documentation commit binds this evidence
index to that immutable source snapshot.

## Evidence map

| Area | State | Evidence |
|---|---|---|
| Runtime business flow and invariants | measured | [Concurrency and live invariants](concurrency-report.md) |
| Unit, integration, architecture, migration and quality checks | measured | [Test report](test-report.md) |
| Formal JMeter and JMH baselines | measured with stated local limitations | [Benchmark report](benchmark-report.md) |
| Kafka, Redis, PostgreSQL and OTel recovery | measured | [Failure drills](failure-drills.md) |
| Image/config scans and final SBOMs | measured | [Security report](security-report.md) |
| Health, metrics, lag and full transaction-to-consumer trace | measured | [Observability](observability.md) |
| Runtime/tool/image identity | measured | [Environment](environment.md) |
| Machine-readable summary | measured/unknown split | [release-evidence.json](release-evidence.json) |

Large raw reports remain under `/tmp` or the Git-ignored `raw/` tree; their paths
and SHA-256 digests are recorded in the relevant reports. This index preserves
commands, summaries, and digests without committing high-volume local artifacts.

Final local verification completed at `2026-09-01T23:50:32-07:00` in
`America/Los_Angeles`.
