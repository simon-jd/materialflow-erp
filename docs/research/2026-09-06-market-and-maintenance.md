# MaterialFlow market and maintenance review — 2026-09-06

## Context restored

The workspace is `/home/postedism/Desktop/Java ERP`. Its read-only empty `.git`
mount is not a repository; real local history is in `.git-local`. Initial HEAD
was `20fdf33` (2026-09-01), with source baseline `431774c`, no remote and a clean
work tree. Read README, Maven modules, source, tests, CI, ADRs/runbooks and the
2026-09-01 evidence. The original charter is
`/home/postedism/Downloads/PROJECT_CHARTER.md`, dated 2026-08-31. Its design-only
status is stale; code and current verification determine implementation state.

Banks are in `/home/postedism/Desktop/Find work/jd-resume-tailor/data/`:
`experience_bank.json` has an `experiences` array;
`project_evidence_bank.json` has schema `1.0`, a `projects` map and an `evidence`
array. Both initially held seven experiences and no MaterialFlow entry.
Current profile says Seattle, MSCS through May 2027, plus prior AI/software
education and existing software engineering work. Another active session updated
the work title/ID while this task ran; those changes are preserved, not reverted.
This is early-career portfolio preparation, not a senior/staff hiring analysis.

Accessible local session JSONL and the memory database were searched for the
bank paths and `Project Experience Bank Generator.md`. No applicable Generator
body or relevant memory record was found. Existing history also records this
missing file. A path clarification was issued; no substitute file is invented.
The current JSON structures, charter and explicit user instructions govern this
update. The user explicitly authorized bank edits in this task.

Other current portfolio entries already cover Go orchestration/fencing,
distributed e-commerce, device delivery, LLM infrastructure and actual local
GPU work. MaterialFlow's useful addition is Java/Spring transactional business
invariants and PostgreSQL/Kafka recovery. It does not gain an ML-serving claim
by borrowing another project's keywords or metrics.

## Official hiring sample

All access/checks below: **2026-09-06**. The requested recent window is roughly
2026-03-06 through 2026-09-06. Search crawl time is never treated as publication
time. Two matching 2027 postings have dates from their official ATS feeds;
Amazon publication dates could not be confirmed. ATS `publishedAt` can reflect
republishing. [Stored metadata](2026-09-06-job-metadata.json) records dates and
current-board membership without archiving entire copyrighted job descriptions.

| Official source | Date and candidate fit | Relevant signals (paraphrased) |
|---|---|---|
| [Arch, Software Engineer, Early Careers 2027](https://jobs.ashbyhq.com/arch.co/9fde8d03-9f47-44ac-bd14-53829722c06d) | Official ATS `publishedAt` 2026-09-02; currently listed; NYC; Dec 2026–Aug 2027 graduation window includes May 2027 | Scoped feature ownership, APIs, reliable maintainable systems, database/debugging work and CS fundamentals. Data-heavy automation is a preference. |
| [Ellipsis Labs, Software Engineer, 2027 New Grads](https://jobs.ashbyhq.com/ellipsislabs/256c2ec2-01c8-4ff6-9ad0-b926fe40472d) | Official ATS `publishedAt` 2026-03-26; currently listed; NYC; explicit 2027 new grads | Design through tests/deployment/monitoring; observability and reliability; project evidence accepted. Rust/TypeScript and DeFi are company preferences. |
| [Amazon, SDE 2026 US, 3177934](https://www.amazon.jobs/en/jobs/3177934/software-development-engineer-2026-us) | Official indexed body accessible; posting date unknown, direct JSON endpoint returned HTTP 406; entry-level, Seattle among locations; 2026 intake/start suitability for a 2027 graduate needs confirmation | General-purpose languages, project/internship evidence, resilient distributed systems, CI/CD, monitoring and debugging. AWS is preferred. |
| [Amazon Annapurna, SDE I ML Infra Services, 10464055](https://www.amazon.jobs/en/jobs/10464055/software-development-engineer-i-ml-infra-services-annapurna-labs) | Official indexed body accessible; posting date unknown; SDE I in Cupertino, internship/project experience accepted; start timing not established | Java plus Go/Python/TypeScript, CI/CD, tooling, metrics and defect root causes. AWS/Kubernetes, Linux profiling and accelerator/compiler exposure are team-specific preferences. |

Official date feeds: [Arch](https://api.ashbyhq.com/posting-api/job-board/arch.co),
[Ellipsis Labs](https://api.ashbyhq.com/posting-api/job-board/ellipsislabs).
Location and level are screened; sponsorship and an actual offer/start date have
not been verified. These are engineering-relevance samples, not an application
eligibility guarantee.

For Cloud Infrastructure comparison, the indexed official
[Astronomer Data Plane Management](https://jobs.ashbyhq.com/astronomer/282736cd-6967-469b-b13d-63b54424ed1c/)
describes early-career stateful workload management, incident review, APIs,
testing and Kubernetes. However, this ID and its Astro Core Services peer were
absent from the current [official board feed](https://api.ashbyhq.com/posting-api/job-board/astronomer).
They are historical role-shape evidence, not confirmed open jobs or dated fresh
demand. The same check excluded the indexed Confluent Cloud Traffic ID; its
Dec 2023–Jan 2026 graduation window also mismatches the current MSCS graduation.
The indexed Abridge early-career ID was absent from its current board as well.

Within the four main postings (three companies), reliability and debugging
recur; tests, deployment/operation and metrics are useful evidence to demonstrate.
This small purposive sample cannot establish a market-wide frequency or trend.
Two Amazon roles are not two independent-company votes. It does not establish
that Kafka, Kubernetes, Spring Boot 4 or an LLM feature is universally required.
AI-assisted development appears in some postings; it is a workflow signal,
not a reason to attach an unimplemented model feature to an ERP.

## Technology review and decision

| Official source checked | Observation and decision |
|---|---|
| [Spring Boot 3.5.16 release, 2026-06-25](https://github.com/spring-projects/spring-boot/releases/tag/v3.5.16) and [official maintenance announcement](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/) | Current pin is a real stable release, but the announcement explicitly calls it the last OSS release of 3.5.x. Keeping it is an explicit local maintenance debt, not a security-current or supported-stack claim. |
| [Cloud supported-version matrix](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions) and [2025.0.3 release, 2026-06-12](https://github.com/spring-cloud/spring-cloud-release/releases/tag/v2025.0.3) | 2025.0 maps to Boot 3.5. Compatibility does not establish continuing OSS maintenance. No independent Cloud major bump. |
| [Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) | Migration changes module/starter layout and Jackson's default major/packages. This repository uses Jackson 2, Boot test/autoconfiguration imports, MyBatis 3.0.5, Cloud Gateway and pinned Netty 4.1. A safe upgrade requires a coordinated compatibility pass and fresh API/security/serialization/deployment tests across five apps. |
| [Spring Kafka 3.3.16 release, 2026-06-09](https://github.com/spring-projects/spring-kafka/releases/tag/v3.3.16) | Current Boot resolves this version and Kafka clients 3.9.2. Release includes header/deserializer and configuration fixes. The missing application claim fence and exhausted-state recovery are our code's defects; library updates do not fix them. |
| [Apache Kafka repository](https://github.com/apache/kafka) and [official downloads/release notes](https://kafka.apache.org/community/downloads/) | GitHub Releases is empty, not evidence of abandonment: Kafka publishes releases through Apache. Retain the existing 4.1.0 broker for a controlled local test; actual broker/client combination is exercised by Testcontainers. Broker patch/security qualification remains a follow-up. |
| [Testcontainers 1.21.4 release](https://github.com/testcontainers/testcontainers-java/releases/tag/1.21.4) | Release addresses newer Docker Engine compatibility. Boot-managed 1.21.4 works with local Docker 29.8.0 in this run. Add only its Kafka test module, plus Boot-managed Flyway test dependencies to run the real schema. No separate runtime middleware added. |
| [PostgreSQL 17 SELECT](https://www.postgresql.org/docs/17/sql-select.html), [Kafka delivery design](https://kafka.apache.org/41/design/design/) | `SKIP LOCKED` fits concurrent queue claiming, not a general consistent read. Kafka idempotent production cannot make PostgreSQL and a fresh application publish one atomic transaction. Preserve the Inbox and explicit at-least-once boundary. |

**Maintenance follow-up:** move to a supported Boot 4.x / matching Cloud train,
first checking current MyBatis, Jackson, gRPC/Netty and test compatibility. Its
real benefit is continuing upstream fixes. It is not performed in this bounded
reliability change because it touches independent HTTP, persistence, security,
serialization and deployment contracts. There is no claimed fresh dependency
or image vulnerability scan. Java 21 remains the working toolchain.

## Priority and acceptance matrix

| Priority / gap | Engineering and role value | Acceptance / current outcome |
|---|---|---|
| P0: final claim stranded and reused-name stale completion | Durable state-machine design and debugging; Java/PostgreSQL evidence complements the Go projects | Two failing baseline oracles; token-aware SQL, bounded recovery and real-schema tests pass |
| P1: ambiguous publish and consumer rollback/replay | Distributed failure reasoning and observability | Actual Kafka sees two offsets for the same event after injected post-ACK failure; PostgreSQL consumer rollback and 12-way duplicate contention preserve one effect; metrics only count committed work |
| P1: alerts disappear after counter reset | Operating-state visibility | Persistent dead-letter gauge, stale-claim counter, Grafana panels and two Promtool rule scenarios pass |
| P2: missing project entry and stale authorization wording | Reviewable, truthful career evidence | Add MaterialFlow only after validation, back up/recheck both current banks, preserve other entries and validate references |
| Deferred: supported framework migration, pool exhaustion, actual process/offset crashes, ordering, deployment rerun | Important remaining maintenance and operating evidence | Explicit gaps; no speedup, production incident or new cloud claim |
