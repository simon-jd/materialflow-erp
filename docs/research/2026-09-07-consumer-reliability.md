# Consumer reliability: market and maintenance review — 2026-09-07

## Context and evidence boundary

Workspace: `/home/postedism/Desktop/Java ERP`; real Git directory `.git-local`,
branch `main`, HEAD `20fdf335fb79ada40ced6a377949af9c1af899d0`; no remote. The
September 6 Outbox update was already uncommitted when this task started. Preserve
it; source hashes, not HEAD permalinks, identify this update. Read the charter,
README, ADRs, core transaction and Inbox/Relay implementation, projection,
configuration, tests and previous local evidence. The ordinary `.git` is an empty
read-only mount.

Banks: `/home/postedism/Desktop/Find work/jd-resume-tailor/data/` with
`experience_bank.json` (`experiences` array) and `project_evidence_bank.json`
(schema 1.0, `projects` map, `evidence` array). Stable project ID:
`project-local-materialflow-erp`. Current `profile.json` says Seattle, MSCS May
2027 and sponsorship needed; actual software engineering work is separately
recorded. Use early-career roles and assess eligibility separately from technical
relevance. Existing projects already cover Go durable workflows, device delivery,
distributed commerce, streaming/lakehouse work and actual ML/GPU qualification.
MaterialFlow's distinguishing evidence is Java transactional business correctness
and Kafka/PostgreSQL recovery, not new ML features.

Local `history.jsonl` was searched for the bank/Generator paths. The memory SQLite
database was queried read-only: no matching relevant memory records. Other remote
conversation history is not exposed. Current files take precedence over historical
pointers. The supplied `/home/postedism/Downloads/Project Experience Bank Generator.md`
is now accessible and was read. Its old Mac paths are stale. Its permissions to
invent production facts/estimated results conflict with this task's explicit
fact-only requirements; those clauses are not used. The explicit task authorizes
project and corresponding bank changes, including refreshed bullets.

## Official job sample

Bodies searched/read on **2026-09-07**. Window: approximately March–September
2026. These are a small purposive sample, not market-share statistics. Crawl dates
are not posting dates. The two ATS dates below come from the retained
[September 6 metadata](2026-09-06-job-metadata.json); today's live ATS metadata
refresh failed (web fetch errors and HTTP 403 via direct requests), so they are
historical metadata observations, not newly reverified board membership.

| Official JD | Date / fit | Engineering signals and interpretation |
|---|---|---|
| [Arch, Early Careers 2027](https://jobs.ashbyhq.com/arch.co/9fde8d03-9f47-44ac-bd14-53829722c06d) | Retained ATS publishedAt 2026-09-02; NYC; Dec 2026–Aug 2027 graduation window | Reliable maintainable code, APIs, database debugging, scoped feature ownership. Fits graduation timing; sponsorship not verified. |
| [Ellipsis Labs, 2027 New Grads](https://jobs.ashbyhq.com/ellipsislabs/256c2ec2-01c8-4ff6-9ad0-b926fe40472d) | Retained ATS publishedAt 2026-03-26; NYC; 2027 graduates | Design, tests, deployment, monitoring, observability and technical tradeoffs. Rust/TypeScript and DeFi are company preferences, not reasons to change this ERP's language. Sponsorship not verified. |
| [Amazon Annapurna, SDE I ML Infra Services, 10464055](https://www.amazon.jobs/en/jobs/10464055/software-development-engineer-i-ml-infra-services-annapurna-labs) | Official body available; posting date/start window unknown; Cupertino; project/internship evidence accepted | Java plus another general-purpose language, CI/CD, automation, metrics and defect root causes. Accelerator/Kubernetes context is specific to the team; this ERP offers transferable reliability evidence only. |
| [Quantcast, Software Engineer Cloud Infrastructure](https://jobs.ashbyhq.com/quantcast/18213524-c947-4be3-b728-497f23dcd5cf) | Official indexed snippet; 0–1 years; posting date unknown; full direct body JS-only | Excluded from suitable application sample: explicitly disallows current/future sponsorship, conflicting with profile. Do not count as a matched job or extrapolate requirements from its title. |

Repeated across independent companies: reliable tested software, debugging and
end-to-end engineering ownership. Operation/metrics are explicit in Ellipsis and
Amazon. No sampled JD establishes that Kafka, Spring Boot 4, an LLM feature or a
new Kubernetes layer is universally required. AI/accelerator interest is a hiring
context, not evidence that adding AI to an ERP has engineering value.

## Official ecosystem review and bounded decision

All sources checked **2026-09-07**. Version existence, compatibility and maintenance
are different questions.

- [Boot 3.5.16 announcement, 2026-06-25](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now)
  explicitly ends OSS support for 3.5. Current pin remains a local maintenance
  debt. [Official releases](https://github.com/spring-projects/spring-boot/releases)
  show ongoing 4.x work; this is not an abandoned ecosystem.
- [Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
  changes starter/module organization, test imports and preferred Jackson major
  and packages. This project uses Jackson 2, MyBatis 3.0.5, Gateway, JPA and pinned
  Netty across five apps. The [Cloud compatibility matrix](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions)
  maps 2025.0 to Boot 3.5 and 2025.1 to Boot 4.0. A supported-stack migration has
  real maintenance benefit but needs coordinated API/JWT/serialization/build and
  deployment validation. No unqualified independent major upgrade in this patch.
- [Spring Kafka official release notes](https://github.com/spring-projects/spring-kafka/releases/tag/v3.3.16)
  and [2026-06-09 release announcement](https://spring.io/blog/2026/06/09/spring-kafka-4/)
  confirm the resolved 3.3.16 patch and its backports. [Repository releases](https://github.com/spring-projects/spring-kafka/releases)
  list 4.1.1/4.0.7 on August 20. Do not override Boot with an incompatible Kafka
  major to solve an application-level retry policy.
- [Versioned error-handling manual](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html)
  supports exception-specific BackOff, fatal classifications, recovery callbacks
  and seek/retry after failed recovery. `commitRecovered` is for MANUAL_IMMEDIATE;
  this project's RECORD mode commits through successful handling/recovery.
  Inspection of the actual cached 3.3.16 class confirms
  `failIfSendResultIsError=true` already by default: preserving this explicitly
  and testing missing-DLQ recovery is verification, not a claimed new loss fix.
- [Apache Kafka repository](https://github.com/apache/kafka) and
  [official downloads](https://kafka.apache.org/community/downloads/) show active
  maintenance: 4.1.2 (2026-03-17) is a supported patch branch release; 4.1.0 is
  archived. Tests retain existing 4.1.0 broker and 3.9.2 client to isolate policy
  behavior. Broker patch qualification remains pending, not security-current.
- [Testcontainers 1.21.4 release](https://github.com/testcontainers/testcontainers-java/releases/tag/1.21.4)
  addresses recent Docker Engine changes. Existing managed dependencies and cached
  images are enough for real broker/database tests; no new dependency is needed.

## Priorities and acceptance

1. Prevent database transient failures from exhausting the ordinary DLQ budget;
   type deterministic envelope failures. Acceptance: real lock timeouts continue
   beyond the old five deliveries without Inbox/offset advancement or DLQ;
   release the lock and observe commit. Use an isolated negative control restoring
   only the old uniform retry selection.
2. Verify recovery through the production listener/factory and transactional Inbox,
   backed by Kafka/PostgreSQL. Assert malformed-record isolation, following-record
   progress, unavailable-DLQ offset retention and recovery after consumer restart.
   Add bounded-cardinality failure/recovery counters and Promtool scenarios.
3. Run full reactor and quality checks; document partition blocking/retention,
   duplicate-DLQ and local-only boundaries; merge corresponding bank records with
   backups, conflict checks and full reference validation.

No performance improvement is proposed or claimed. These are correctness and
fault-recovery assertions, not throughput or latency benchmarks. No framework
migration, cloud deployment, production incident or real JVM kill is part of this
bounded implementation.

The real PostgreSQL fault exposed SQLSTATE `55P03` (`lock_not_available`) wrapped
as `UncategorizedSQLException` by the current JDBC translator. The policy handles
this specific code through wrapped causes; it does not match error text or classify
all SQL failures as transient. [PostgreSQL 17 error codes](https://www.postgresql.org/docs/17/errcodes-appendix.html)
recommend code-based classification. Schema/constraint errors retain bounded recovery.
