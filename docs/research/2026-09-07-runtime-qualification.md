# Runtime qualification research — 2026-09-07

This continues the same day's [official JD review](2026-09-07-consumer-reliability.md).
Arch's 2027 early-career role, Ellipsis Labs' 2027 new-grad role and Amazon's
Annapurna SDE I ML Infrastructure role support testing, debugging, maintainable
backends and operational ownership as repeated signals in this small sample.
They do not establish a market-wide Spring Boot 4 requirement. Graduation timing,
sponsorship uncertainty and the profile's actual experience remain unchanged.

For this Java transactional backend, the additional gap is running the changed
system as actual applications, proving process recovery and qualifying maintenance
changes. Other bank projects already supply ML/GPU and infrastructure breadth.
Adding an LLM, changing languages or adding another orchestration layer would not
strengthen this project's distinctive evidence.

All links checked on **2026-09-07**; dates below are release/advisory dates, not
search crawl dates. These are official project sources.

| Source | Dated fact and decision |
|---|---|
| [Boot 3.5.16](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now) | 2026-06-25; ends OSS support for 3.5, creating maintenance debt. |
| [Boot 4.0.8 release](https://github.com/spring-projects/spring-boot/releases/tag/v4.0.8) | 2026-08-21; select stable 4.0 family, not 4.2 milestones. |
| [Cloud supported versions](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions), [2025.1.2 release](https://github.com/spring-cloud/spring-cloud-release/releases/tag/v2025.1.2) | Compatibility matrix maps 2025.1 to Boot 4.0; BOM released 2026-06-12, Gateway 5.0.2. |
| [MyBatis releases](https://github.com/mybatis/spring-boot-starter/releases) | 4.0.1 released 2025-12-28 for Boot 4.0; latest 4.1.0 targets Boot 4.1 and is not the selected family. |
| [Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide), [Jackson 3 notes](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.0) | Living guides: explicit modular starters, test support and Jackson packages require source changes. Boot 4.0.8 resolves Jackson 3.1.5; no deprecated Jackson 2 bridge added. |
| [Resilience4j 2.4.0](https://github.com/resilience4j/resilience4j/releases/tag/v2.4.0) | 2026-03-14; adds Boot 4 / Cloud 5 support. Match the Boot 4 integration module. |
| [Protobuf 33.5](https://github.com/protocolbuffers/protobuf/releases/tag/v33.5), [runtime guarantee](https://protobuf.dev/support/cross-version-runtime-guarantee/) | 2026-01-29 release; newer generated code cannot use an older runtime. Actual OTel startup failure justifies the move from Java/protoc 4.31.1 to 4.33.5. |
| [Kafka downloads](https://kafka.apache.org/community/downloads/) | 4.1.2 dated 2026-03-17, same patch line as existing broker; align with Boot-managed clients/Streams. |
| [PostgreSQL 17.11 notes](https://www.postgresql.org/docs/release/17.11/) | 2026-08-13; patch update within PostgreSQL 17, preserve real Flyway and locking regression tests. |
| [Tomcat 11 security advisories](https://tomcat.apache.org/security-11.html), [11.0 changelog](https://tomcat.apache.org/tomcat-11.0-doc/changelog.html) | 11.0.25 dated 2026-08-18; relevant advisories public 2026-08-25. Actual Trivy findings in Boot's 11.0.24 warrant a patch override, followed by full verification. Scanner/Apache severity ratings differ; no exploitability claim. |
| [Trivy 0.74.0](https://github.com/aquasecurity/trivy/releases/tag/v0.74.0), [JMeter official download](https://jmeter.apache.org/download_jmeter.cgi) | Trivy released 2026-08-14; refresh scanner data rather than treating September 1 results as current. JMeter 5.6.3 distribution checked against official SHA-512. |

Acceptance: clean tests and static analysis; five exact-JAR Docker images and
current vulnerability scans; JWT, projection, gRPC and trace smoke checks; two
real process-kill windows; three 60-second warmups plus 180-second measurements
per variant. Record failures before fixes, environment and raw-data hashes. Only
executed outcomes may enter the banks. The [ADR](../adr/0007-boot4-runtime-qualification.md)
explains compatibility cost and rollback scope; the evidence index is authoritative
for which acceptance checks actually completed.

Actual deployment also exercised a documented Kafka 4 default change:
[producer configuration](https://kafka.apache.org/41/configuration/producer-configs/)
changes `linger.ms` from 0 to 5 and requires delivery timeout to cover request
timeout plus linger. The old Relay settings violated that constraint. Keeping
linger explicitly at zero is useful for its serial claim/send/ACK design; the
change is now covered using the application's actual YAML in the Kafka test.
