# MaterialFlow ERP

## Experience Bank results

The results below are the owner-confirmed results from a separate cloud-hosted test environment, synchronized from the Experience Bank. The experiments retain the local, synthetic, simulator, CPU, Docker and single-host boundaries stated in each result; cloud hosting does not imply production deployment. This repository refresh does not represent a rerun of those measurements. Earlier dated evidence below remains tied to its own source, configuration and denominator.

1. Built a simulated Java 21/Spring Boot 4.0.8 ERP backend on PostgreSQL 17.6 where inventory debit, credit reservation, the idempotent order and audit/outbox records commit together and the database commit is the order-success boundary; the 60-case suite splits into 20 order-transaction, 18 relay (including 8 component-scenario designs, which are not added on top of the 18), 12 consumer and 10 configuration-migration cases.

2. Fenced every relay claim with a per-claim random claim_token on the outbox completion UPDATE (batch 50, 30 s lease, recovery of 100 rows per second), so a worker that revives after lease expiry cannot overwrite a newer claimant's completion.

3. Absorbed at-least-once delivery downstream with an inbox(event_id) unique key in the same transaction as the business update: 1,200 Kafka deliveries (the 1,000 order events plus 200 duplicate publishes) produced 1,000 inbox rows and exactly 1,000 business updates with 200 duplicates recognized, while the 1,000 order submissions that were each re-sent once were absorbed by the order idempotency key.

4. Covered the four fault windows with 20 simulated kills (4 windows x 5 each) in a separate fixture that does not change the 1,200-delivery denominator: the inbox-insert-uncommitted and inbox-committed/offset-uncommitted windows still converge to one business update, the post-Kafka-ACK window allows multiple messages with the same event_id, and after a DLQ ACK the DLQ may repeat while the source offset eventually advances.

5. Kept failure handling explicit: permanently invalid events go to a DLQ, database lock timeouts back off, and at the backoff cap the offset is not advanced and an alert fires instead of silently dropping orders.

6. Recorded capped-rate latency comparisons of 3 rounds x 180 s x 10 requests/s = 5,400 requests per version with the HTTP-receive-to-order-commit endpoint and the outbox.created_at-to-downstream-commit event latency recorded separately; the equal arrival rate supports no throughput-improvement claim. The five-app topology (100 SKUs, 1,000 customers, one order line and amount 100 per order) runs on an 8-core/16 GiB local Docker host with Kafka clients/Streams 4.1.2, and health, JWT, gRPC and tracing connectivity is a separate acceptance denominator from the 60 JUnit cases.

See the [implementation and reproduction map](docs/experience-bank-alignment.md) for per-result source/tests, reproduction commands and limitations.

MaterialFlow ERP is a private Java 21 reference backend for a synthetic
building-material business. PostgreSQL is the source of truth; Redis and Kafka are
recoverable acceleration and integration planes.

## Implemented scope

- Modular transactional core for pricing, sales orders, credit, approval,
  inventory, procurement, fulfillment, invoicing, synthetic payment import and
  reconciliation.
- PostgreSQL idempotency, immutable ledgers/audit records, Transactional Outbox,
  Consumer Inbox, reservation expiry and fixed-order credit/inventory locking.
- Consumer failure classification keeps recoverable database work at the source
  with capped backoff, quarantines malformed envelopes, and exposes retry/DLQ
  metrics; real broker/database tests cover offsets and consumer restart.
- Kafka Relay with multi-instance `SKIP LOCKED` claiming, per-claim fencing,
  exhausted-lease recovery, bounded retry and dead lettering; Kafka Streams
  rebuildable projections.
- Reactive Gateway with optional Keycloak JWT validation, Redis rate limiting and
  fail-closed sensitive writes; Protocol Buffers/gRPC synthetic integrations with
  Resilience4j.
- Compose, Helm, optional safety-gated Terraform, OpenTelemetry, Prometheus,
  Grafana, Jaeger, CI gates, JMH and JMeter workloads.

This is a portfolio-scale reference implementation, not a production ERP. Claims
in `docs/evidence` distinguish `measured`, `derived`, `estimated`, and `unknown`.
No production SLA or unmeasured throughput is claimed.

The 2026-09-01 local verification index is
[`docs/evidence/local-2026-09-01/README.md`](docs/evidence/local-2026-09-01/README.md).
The formal three-run long-duration performance protocol, JWT/RBAC and tenant
isolation checks, kind Pod/rolling-update drills, and one complete
API-to-database-to-Outbox-to-Kafka-consumer trace are now locally verified. The
implementation remains an `implemented_local_reference`, not evidence of real
production tenure, customers, team ownership, cloud SLA, or past employment.
The [2026-09-06 reliability update](docs/evidence/local-2026-09-06/README.md)
adds verified Relay recovery and transactional consumer-metric fixes. It records
current tests separately from the historical performance/deployment baseline.
The corresponding Experience/Evidence Bank entries were authorized by the user
for this update; their synchronization record is in the new evidence index.
The [2026-09-07 consumer update](docs/evidence/local-2026-09-07/README.md)
adds database-outage retry classification and actual consumer-offset recovery
tests. Its [decision record](docs/adr/0006-consumer-failure-classification.md)
explains partition blocking and retained at-least-once delivery; the
[consumer runbook](docs/runbooks/consumer-recovery.md) covers diagnosis and replay.

The runtime now uses Spring Boot 4.0.8 / Cloud 2025.1.2, Jackson 3, MyBatis Starter
4.0.1 and Resilience4j 2.4.0 on Java 21. Tomcat 11.0.25 addresses the published
August advisories; Protobuf compiler/runtime 4.33.5 resolves an observed OTel
startup incompatibility. PostgreSQL 17.11 and Kafka 4.1.2 are the selected local
runtime patches. The [migration decision](docs/adr/0007-boot4-runtime-qualification.md)
records benefits, compatibility costs and rollback limits.

The [runtime qualification index](docs/evidence/local-2026-09-07-qualification/README.md)
separates current container/test/measurement results from all earlier snapshots.
Use the [qualification runbook](docs/runbooks/runtime-qualification.md) for the
isolated JWT-enabled Compose stack, exact-JAR image packaging, bounded JMeter
protocol and actual process-kill drills. These remain local synthetic experiments.

## Build

```bash
mvn verify
MATERIALFLOW_REDIS_PORT=6389 docker compose -f deploy/compose/compose.yml up -d
curl http://localhost:8081/actuator/health
```

If a default host port is occupied, override it, for example
`MATERIALFLOW_REDIS_PORT=6389 docker compose ...`. The API contract is
`contracts/openapi/materialflow-erp-v1.yaml`; all records in the demo migrations
are synthetic.

Infrastructure and local run instructions live in `deploy/compose` and
`docs/runbooks/local-development.md`. Architecture decisions and operational
recovery steps are under `docs/adr` and `docs/runbooks`.

## Privacy

All organizations, users, materials, prices, accounts, and transactions are
synthetic. The owner has authorized synchronization to the private GitHub repository. Runtime credentials and private local state are excluded from publication.
