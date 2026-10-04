# Experience Bank implementation map

Result provenance: owner-confirmed separate cloud-hosted test results. The README reproduces the current selected bullets. Commands below exercise this checkout; new outcomes must be recorded separately from those supplied results.

| Result | Implementation and regression evidence | Reproduce | Scope / source difference |
|---|---|---|---|
| 1 | [apps/erp-core/src/main/java/com/materialflow/erp/order/OrderApplicationService.java](../apps/erp-core/src/main/java/com/materialflow/erp/order/OrderApplicationService.java) · [apps/erp-core/src/test/java/com/materialflow/erp/integration/OrderConcurrencyIntegrationTest.java](../apps/erp-core/src/test/java/com/materialflow/erp/integration/OrderConcurrencyIntegrationTest.java) | `mvn -B -Pquality verify` | JUnit counts belong to their own run; the 60-case cloud suite includes its 8 relay designs within 18 relay cases. |
| 2 | [apps/event-relay/src/main/java/com/materialflow/relay/outbox/OutboxRelay.java](../apps/event-relay/src/main/java/com/materialflow/relay/outbox/OutboxRelay.java) · [apps/event-relay/src/test/java/com/materialflow/relay/outbox/OutboxRelayTest.java](../apps/event-relay/src/test/java/com/materialflow/relay/outbox/OutboxRelayTest.java) | `mvn -B -pl apps/event-relay -am test` | Per-claim equality fence protects database completion, not Kafka sends. |
| 3 | [apps/erp-core/src/test/java/com/materialflow/erp/events/DomainEventConsumerRecoveryIntegrationTest.java](../apps/erp-core/src/test/java/com/materialflow/erp/events/DomainEventConsumerRecoveryIntegrationTest.java) | `mvn -B -pl apps/erp-core -am test` | Inbox/business commit and Kafka offset are distinct boundaries. |
| 4 | [tests/failure/process-kill-recovery.py](../tests/failure/process-kill-recovery.py) · [tests/failure/README.md](../tests/failure/README.md) | `python3 tests/failure/process-kill-recovery.py --help` | Four-window cloud fixture and historical two-process-kill workload are separate denominators. |
| 5 | [docs/runbooks/consumer-recovery.md](../docs/runbooks/consumer-recovery.md) · [apps/erp-core/src/main/java/com/materialflow/erp/events/KafkaConsumerConfiguration.java](../apps/erp-core/src/main/java/com/materialflow/erp/events/KafkaConsumerConfiguration.java) | `mvn -B -pl apps/erp-core -am test` | Transient database errors retain source offset; invalid input routes through DLQ. |
| 6 | [tests/performance/orders-load.jmx](../tests/performance/orders-load.jmx) · [tests/performance/summarize_jtl.py](../tests/performance/summarize_jtl.py) · [docs/runbooks/runtime-qualification.md](../docs/runbooks/runtime-qualification.md) | `python3 -m unittest discover -s tests/performance -p "test_*.py"` | Capped arrivals do not establish throughput improvement; deployment acceptance is separate. |

## Measurement boundaries

- The 60-case suite and the 20 simulated kills are simulated designs for this local lab; the historical anchors are the earlier 56 JUnit cases and two real SIGKILLs, and the two rounds must not be conflated.
- There is no cross-system atomic commit between PostgreSQL and Kafka. Outbox records, relay retry and inbox dedup give at-least-once delivery; Kafka records are not exactly-once, and inbox behavior cannot be used to infer that they are.
- The 18 relay tests include the 8 component-scenario designs; do not present 8 + 18 as separate counts.
- 1,200 deliveries to 1,000 business updates is the fixed replay; the 4-window, 20-kill fault matrix is a separate fixture and is not part of the 1,200 denominator. The Kafka-ACK window can leave multiple same-event_id messages, and the DLQ may repeat, so exactly-once must not be claimed.
- claim_token is a per-claim random equality fence, needed because the same instance can reclaim after lease expiry; it fences database completion, not Kafka sends, and ShedLock does not replace per-row claim fencing or business idempotency.
- 5,400 requests per version at a fixed 10 requests/s arrival rate is a capped-rate comparison, not a throughput or saturation claim, and the two timing endpoints are recorded separately.
- Single-node Kafka broker 4.1.0 in an 8-core/16 GiB local Docker environment with synthetic data; no multi-node, partition, retention-expiry, production-scale, cloud or ML/GPU claim.
- The 60 JUnit cases do not prove the five Docker applications run together; health, JWT, gRPC and tracing connectivity is a separate acceptance denominator.

## Local verification

See `docs/alignment-verification.json` for commands and outcomes from this checkout. Supplied cloud numbers, historical checked-in artifacts and new local checks are separate evidence sets. A skipped dependency test is not a pass.
