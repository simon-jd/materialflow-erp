# ADR 0007: qualify a supported Spring stack as one runtime change

Date: 2026-09-07. Status: accepted; execution results are recorded separately in
[the qualification evidence](../evidence/local-2026-09-07-qualification/README.md).

## Problem and decision

Boot 3.5.16 ended its OSS maintenance line. MaterialFlow uses five applications,
JPA/JDBC/MyBatis, WebFlux Gateway, Kafka, Jackson, gRPC and instrumentation; changing
only the parent version would leave meaningful runtime compatibility unchecked.

Use Java 21 with Boot 4.0.8, Cloud 2025.1.2 and MyBatis Starter 4.0.1. This is the
matching Boot 4.0 family; adopting Boot 4.1 would also require a different Cloud
and MyBatis family without addressing a separate project requirement. Keep the
Boot-managed Kafka 4.1.2 client/Streams and Spring Kafka 4.0.7 together. Qualify
Kafka broker 4.1.2 and PostgreSQL 17.11 in actual local containers.

Migrate application JSON imports to Jackson 3, explicit Kafka/Flyway/security/
Web MVC/AspectJ starters, Boot 4 health packages and Testcontainers 2.0.5 modules.
Remove the old Netty 4.1 override so WebFlux uses its matching Boot-managed Netty
family. Use the OpenTelemetry starter and its new trace endpoint property, while
explicitly retaining Prometheus scraping and stdout logging instead of enabling
unconfigured OTLP metrics/log exporters.

Because idempotency hashes depend on serialized request representation, capture
an actual create-order record from the Boot 3 Docker baseline and replay that
persisted hash/response through Boot 4 against PostgreSQL. The regression passes
without an additional mapper override and creates no new order. This is evidence
for the specific legacy order shape, not exhaustive backward compatibility or a
claimed hash bug fix. No compatibility switch is added without a failing case.

Use Resilience4j 2.4.0's Boot 4 integration and test both application startup and
configured proxy/registry presence. The initial clean test uncovered OTel gencode
requiring Protobuf >=4.32 against the old 4.31.1 runtime. Align our protoc/runtime
at 4.33.5; retain gRPC 1.75.0 because this migration does not require a new RPC API.

The initial image scan detected three Critical-rated Tomcat findings with a
published 11.0.25 fix. Override Boot's 11.0.24 with that patch. Scanner severity
is not a claim that these configurations are exploitable in MaterialFlow; Apache
rates the affected features separately. Review/remove this override when the BOM
catches up. Record the scan DB timestamp and exact final image IDs.

## Validation and rollback boundaries

Run a **clean** reactor: incremental compilation can retain classes from the old
dependency graph. Preserve the pre-migration sources and JARs. Testcontainers
exercise real migrations, locks, rollback, duplicate consumption and recovery;
Checkstyle/SpotBugs and Promtool remain gates. Build runtime images from the
verified JARs, recording JAR hashes and image IDs, rather than recompiling during
image packaging.

Deploy an isolated localhost-only Compose project with synthetic data, JWT enabled,
non-root/read-only application containers, 2 CPU and 1 GiB per application. Check
five health endpoints, JWT/tenant boundaries, projections, synthetic gRPC and an
actual exported trace. Before/after JMeter runs use the same supporting image
versions and separate fresh state; the written protocol bounds the conclusions.

Two external fixture triggers make actual SIGKILL windows observable: an Inbox
INSERT before commit, and an Outbox PUBLISHED write after Kafka ACK. They gate
only a unique test event, are removed in `finally`, and are never schema migrations.
Kafka ACK and database completion remain separate: the relay drill expects two
broker offsets for one event ID after restart. The consumer probe checks Inbox
deduplication, with inventory-effect invariants covered separately in JUnit.

No business schema change is needed for Boot 4. The V6 token migration belongs to
the earlier relay change and retains its coordinated rollout requirement. Local
rollback uses the saved Boot 3 JARs and matching old source; do not advertise a
mixed-version production rollout or downgrade Kafka/Redis volumes in place.

## Costs and remaining limits

Fresh Compose validation exposed two additional runtime defects. Streams can
enter ERROR when its source topic does not yet exist; an idempotent `kafka-init`
service now provisions the domain and both DLQs before dependent apps start.
Kafka 4 also changes default linger from 0 to 5 ms, invalidating the Relay's tight
30-second delivery/request timeout pair. Explicit `linger.ms=0` preserves its
single-send-and-wait behavior, where waiting cannot combine subsequent records.
The Kafka recovery test now binds the actual application YAML instead of hiding
this mismatch with a separate producer configuration. Consumer scenarios likewise
use the configured factory; a new group must replay pre-existing events using an
explicit earliest reset policy. Existing groups still use their committed offsets.

The coordinated migration changes dependency/module boundaries and serialization
APIs across five apps. It is justified by maintenance and actual compatibility
failures, not a new hiring keyword or presumed speedup. The short, rate-limited,
shared-host performance protocol is a regression observation, not saturation
capacity, a latency SLA or a causal optimization estimate. Local single-broker
recovery is not multi-node fault tolerance, cloud operation or production tenure.

Source dates, compatibility links and role-demand rationale are in the
[research note](../research/2026-09-07-runtime-qualification.md).
